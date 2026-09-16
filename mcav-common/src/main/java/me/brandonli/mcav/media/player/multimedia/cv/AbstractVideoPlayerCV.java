/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.media.player.multimedia.cv;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of players built on JavaCV frame grabbers, which covers FFmpeg, OpenCV, and capture devices.
 *
 * <p>Every playback runs in a {@link PlaybackSession} with its own threads. Pausing freezes the playback clock
 * while decoding fills the queues, so resuming is instant. Seeking creates a new session at the wanted position,
 * because grabbers cannot be repositioned safely from another thread. Media without a known length, such as live
 * streams and cameras, cannot be seeked.
 *
 * <p>Frames are delivered as 8-bit BGR and audio as signed 16-bit little-endian stereo at 48 kHz regardless of
 * the source, so pipelines never see other formats. When a {@link DimensionAttachableCallback} is attached, frames
 * are delivered at that size: FFmpeg scales while it decodes, which is much cheaper than resizing later in the
 * pipeline, and frames of decoders that ignore the requested size, such as the file reader of OpenCV, are scaled
 * right after decoding.
 *
 * <p>Starting, seeking, and releasing wait until the previous playback has stopped, which takes a few milliseconds
 * but up to several seconds when a decoder is stuck in a slow network read. Call the asynchronous variants, such as
 * {@link #releaseAsync()}, from threads that must not block.
 */
public abstract class AbstractVideoPlayerCV implements VideoPlayerMultiplexer {

  private static final long MICROS_PER_MILLI = 1_000L;
  private static final String NETWORK_TIMEOUT_MICROS = "15000000";

  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final DimensionAttachableCallback dimensionCallback;
  private final Lock lock;

  private volatile BiConsumer<String, Throwable> exceptionHandler;
  private volatile long maxVideoLagNanos;
  private volatile @Nullable PlaybackSession session;
  private @Nullable Source videoSource;
  private @Nullable Source audioSource;
  private boolean released;

  /**
   * Constructs a new player with detached pipelines and the default exception handler, which logs errors.
   */
  protected AbstractVideoPlayerCV() {
    final ExceptionHandler defaultHandler = ExceptionHandler.createDefault();
    this.videoCallback = VideoAttachableCallback.create();
    this.audioCallback = AudioAttachableCallback.create();
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.lock = new ReentrantLock();
    this.exceptionHandler = defaultHandler.getExceptionHandler();
    this.maxVideoLagNanos = PlaybackSession.MAX_VIDEO_LAG_NANOS;
  }

  /**
   * Creates the grabber that decodes a resource. The grabber is configured and started by the player.
   *
   * @param resource the resource to decode, such as a file path, a URL, or a device index
   * @return a new, unstarted grabber
   */
  protected abstract FrameGrabber createFrameGrabber(final String resource);

  /**
   * Starts playing a source, replacing the current playback. The current playback keeps playing if the new source
   * cannot be opened.
   *
   * @param combined the source
   * @return true if playback started, false if the player is released or the source cannot be opened, which is
   * reported to the exception handler
   */
  @Override
  public boolean start(final Source combined) {
    Preconditions.checkNotNull(combined, "Source must not be null");
    return this.startSources(combined, null, 0L, false);
  }

  /**
   * Starts playing video from one source and audio from another, replacing the current playback. Equal sources are
   * played as one combined source. The current playback keeps playing if the video source cannot be opened.
   *
   * @param video the video source
   * @param audio the audio source
   * @return true if playback started, false if the player is released or the video source cannot be opened, which
   * is reported to the exception handler
   */
  @Override
  public boolean start(final Source video, final Source audio) {
    Preconditions.checkNotNull(video, "Video source must not be null");
    Preconditions.checkNotNull(audio, "Audio source must not be null");
    final boolean sameSource = video.equals(audio);
    if (sameSource) {
      return this.startSources(video, null, 0L, false);
    }
    return this.startSources(video, audio, 0L, false);
  }

  private PlaybackSession createSession(final Source video, final @Nullable Source audio, final long positionMicros, final boolean paused) {
    final PlaybackSession.GrabberFactory videoFactory = () -> this.createStartedGrabber(video);
    final PlaybackSession.GrabberFactory audioFactory = audio == null ? null : () -> this.createStartedGrabber(audio);
    return new PlaybackSession(
      videoFactory,
      audioFactory,
      this.videoCallback,
      this.audioCallback,
      this.dimensionCallback,
      this.exceptionHandler,
      positionMicros,
      paused,
      PlaybackSession.AUDIO_LEAD_NANOS,
      this.maxVideoLagNanos,
      System::nanoTime
    );
  }

  /**
   * Makes the sessions started afterward show every frame however late it is, for tests whose frame counts must not
   * depend on the load of the machine. Players drop frames more than {@link PlaybackSession#MAX_VIDEO_LAG_NANOS} late
   * otherwise.
   */
  @VisibleForTesting
  void neverDropLateFrames() {
    this.maxVideoLagNanos = Long.MAX_VALUE;
  }

  /**
   * Opens the sources in a new session and only then replaces the current session with it, so a source that cannot
   * be opened leaves the current playback alone.
   */
  private boolean startSources(final Source video, final @Nullable Source audio, final long positionMicros, final boolean paused) {
    this.lock.lock();
    try {
      if (this.released) {
        return false;
      }
      final PlaybackSession created = this.createSession(video, audio, positionMicros, paused);
      final boolean opened = this.openSession(created, video);
      if (!opened) {
        return false;
      }
      this.stopSession();
      this.videoSource = video;
      this.audioSource = audio;
      created.startThreads();
      this.session = created;
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Opens a session, reporting every failure: grabbers throw checked exceptions for sources they cannot open,
   * runtime exceptions for invalid options, and linkage errors when their native libraries cannot be loaded.
   */
  private boolean openSession(final PlaybackSession created, final Source video) {
    try {
      created.open();
      return true;
    } catch (final FrameGrabber.Exception | RuntimeException | LinkageError exception) {
      final String resource = video.getResource();
      this.exceptionHandler.accept("Failed to start playback of " + resource, exception);
      return false;
    }
  }

  private FrameGrabber createStartedGrabber(final Source source) throws FrameGrabber.Exception {
    final String resource = source.getResource();
    final FrameGrabber grabber = this.createFrameGrabber(resource);
    try {
      this.configureGrabber(grabber, source);
      grabber.start();
      return grabber;
    } catch (final FrameGrabber.Exception | RuntimeException | LinkageError exception) {
      // a grabber that failed to start may already hold native resources, such as an open file
      closeQuietly(grabber);
      throw exception;
    }
  }

  private static void closeQuietly(final FrameGrabber grabber) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception | RuntimeException exception) {
      // the grabber failed already, the failure to close it adds nothing
    }
  }

  /**
   * Configures a grabber before it is started. Subclasses may override this method to add options, but should
   * call the base implementation to keep the output formats the pipelines expect.
   *
   * @param grabber the grabber to configure
   * @param source  the source the grabber decodes
   * @throws NullPointerException if the grabber or the source is null
   */
  protected void configureGrabber(final FrameGrabber grabber, final Source source) {
    Preconditions.checkNotNull(grabber, "Grabber must not be null");
    Preconditions.checkNotNull(source, "Source must not be null");
    grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
    grabber.setSampleRate(AudioFilter.SAMPLE_RATE);
    grabber.setAudioChannels(AudioFilter.CHANNELS);
    grabber.setImageScalingFlags(swscale.SWS_AREA);
    final boolean scale = this.dimensionCallback.isAttached();
    if (scale) {
      final Dimension dimension = this.dimensionCallback.retrieve();
      final int width = dimension.getWidth();
      final int height = dimension.getHeight();
      grabber.setImageWidth(width);
      grabber.setImageHeight(height);
    }
    if (grabber instanceof final FFmpegFrameGrabber ffmpeg) {
      configureFFmpeg(ffmpeg, source);
    }
  }

  private static void configureFFmpeg(final FFmpegFrameGrabber grabber, final Source source) {
    grabber.setVideoOption("threads", "auto");
    grabber.setVideoOption("flags", "low_delay");
    grabber.setOption("rw_timeout", NETWORK_TIMEOUT_MICROS);
    grabber.setOption("reconnect", "1");
    grabber.setOption("reconnect_streamed", "1");
    grabber.setOption("reconnect_delay_max", "5");
    grabber.setOption("fflags", "discardcorrupt+genpts");
    grabber.setOption("hwaccel", "auto");
    if (source instanceof final FFmpegDirectSource direct) {
      final String format = direct.getFormat();
      grabber.setFormat(format);
      grabber.setOption("probesize", "32");
      grabber.setOption("analyzeduration", "0");
    }
  }

  /**
   * Pauses playback by freezing the playback clock. Decoding goes on until the queues are full, so resuming is
   * instant.
   *
   * @return true if playback was paused, false if nothing is playing or it was already paused
   */
  @Override
  public boolean pause() {
    this.lock.lock();
    try {
      final PlaybackSession current = this.session;
      if (current == null || !current.isActive() || current.isPaused()) {
        return false;
      }
      current.pause();
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Resumes a paused playback. Playback that has ended is not restarted; start the source again for that, which
   * behaves the same with every player.
   *
   * @return true if playback was resumed, false if nothing is playing or it was not paused
   */
  @Override
  public boolean resume() {
    this.lock.lock();
    try {
      final PlaybackSession current = this.session;
      if (current == null || !current.isActive() || !current.isPaused()) {
        return false;
      }
      current.resume();
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Jumps to a position by starting a new session there. A paused player stays paused, and media that has ended
   * plays again from the position.
   *
   * @param time the position in milliseconds from the start of the media, not negative
   * @return true if the player seeked, false if nothing was played, the media cannot be seeked, such as a live
   * stream or a camera, or it cannot be opened again
   */
  @Override
  public boolean seek(final long time) {
    Preconditions.checkArgument(time >= 0, "Seek time must not be negative");
    this.lock.lock();
    try {
      final PlaybackSession current = this.session;
      if (current == null || !current.isSeekable()) {
        return false;
      }
      final Source video = Objects.requireNonNull(this.videoSource, "A session always has a video source");
      final boolean paused = current.isPaused();
      final long positionMicros = time * MICROS_PER_MILLI;
      return this.startSources(video, this.audioSource, positionMicros, paused);
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Gets the playback position, which is the timestamp of the last rendered frame.
   *
   * @return the position in milliseconds, or 0 if nothing has been played
   */
  public long getPositionMillis() {
    final PlaybackSession current = this.session;
    if (current == null) {
      return 0L;
    }
    final long micros = current.getPositionMicros();
    return micros / MICROS_PER_MILLI;
  }

  /**
   * Checks whether media is currently being played or paused, as opposed to stopped or finished.
   *
   * @return true if a playback session is active
   */
  public boolean isPlaying() {
    final PlaybackSession current = this.session;
    return current != null && current.isActive();
  }

  /**
   * Stops playback and releases the player. A released player cannot be started again. The threads of the playback
   * are awaited without holding the lock of the player, so other methods return right away meanwhile, but this
   * method can block for seconds when a decoder is stuck; see {@link #releaseAsync()}.
   *
   * @return true if the player was released, false if it was already released
   */
  @Override
  public boolean release() {
    final PlaybackSession stopped;
    this.lock.lock();
    try {
      if (this.released) {
        return false;
      }
      this.released = true;
      stopped = this.session;
      this.session = null;
      this.videoSource = null;
      this.audioSource = null;
    } finally {
      this.lock.unlock();
    }
    if (stopped != null) {
      stopped.stop();
    }
    return true;
  }

  private void stopSession() {
    final PlaybackSession current = this.session;
    if (current == null) {
      return;
    }
    this.session = null;
    current.stop();
  }

  /**
   * Gets the slot that holds the video pipeline. Frames reach the pipeline as 8-bit BGR on the video rendering thread
   * of the playback, and the pipeline can be swapped while playing.
   *
   * @return the video pipeline slot
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  /**
   * Gets the slot that holds the audio pipeline. Samples reach the pipeline as signed 16-bit little-endian stereo at
   * 48 kHz on the audio rendering thread of the playback, and the pipeline can be swapped while playing.
   *
   * @return the audio pipeline slot
   */
  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  /**
   * Gets the slot that holds the size frames are scaled to before they reach the video pipeline. The size is read
   * when a source is opened, so attach it before starting or seeking.
   *
   * @return the target size slot
   */
  @Override
  public DimensionAttachableCallback getDimensionAttachableCallback() {
    return this.dimensionCallback;
  }

  /**
   * Gets the handler that receives failures to open, decode, and filter media, which logs them by default.
   *
   * @return the current handler
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler;
  }

  /**
   * Sets the handler that receives failures to open, decode, and filter media. Sessions started later use the new
   * handler. The handler is called on the thread that failed, so it must be fast and must not throw.
   *
   * @param exceptionHandler the new handler
   * @throws NullPointerException if the handler is null
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.exceptionHandler = exceptionHandler;
  }
}
