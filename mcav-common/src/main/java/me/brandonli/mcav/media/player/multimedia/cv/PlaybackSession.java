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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One playback of one media source, or of one video source with a separate audio source.
 *
 * <p>A session owns up to four threads. One decoding thread per grabber pulls frames out of FFmpeg or OpenCV as
 * fast as the bounded queues allow, so decoding never blocks on the pipelines. A video rendering thread waits until
 * each frame is due according to the {@link PlaybackClock}, then runs the video pipeline; frames that are more than
 * 100 ms late are dropped, so a slow pipeline lowers the frame rate instead of drifting out of sync. An audio
 * rendering thread hands each chunk to the audio pipeline slightly before it is due, which keeps downstream buffers
 * full without running ahead of the video.
 *
 * <p>Pictures are copied out of the decoder into a small {@link ImagePool}: the rendering thread hands every image
 * back once the pipeline ran, so no memory is allocated per frame. Frames that have another size than the attached
 * dimension are scaled on the decoding thread, see {@link VideoFrameCopier}. While no video pipeline is attached,
 * frames are queued without their picture, which keeps the clock, the position, and the end of the media right
 * without copying anything.
 *
 * <p>When a decoding thread reaches the end of its media, it puts an end marker into the queues it fills, and each
 * rendering thread finishes once it takes its marker. There is no polling and no race between the end of decoding
 * and the last queued frames. Stopping a session interrupts the threads, replaces the queued media with end
 * markers, and waits for the threads to exit. A pipeline may even stop the session from its own rendering thread.
 * Sessions cannot be restarted; seeking or resuming after a stop creates a new session.
 */
final class PlaybackSession {

  private static final int VIDEO_QUEUE_CAPACITY = 4;
  private static final int IMAGE_POOL_CAPACITY = VIDEO_QUEUE_CAPACITY + 2;
  private static final int AUDIO_QUEUE_CAPACITY = 64;
  private static final long PAUSE_POLL_MILLIS = 50L;
  /**
   * How late a frame may be before players drop it instead of showing it, in nanoseconds.
   */
  static final long MAX_VIDEO_LAG_NANOS = 100_000_000L;

  /**
   * How long before its timestamp players hand an audio chunk to the pipeline, in nanoseconds.
   */
  static final long AUDIO_LEAD_NANOS = 50_000_000L;

  private static final long MAX_SLEEP_NANOS = 500_000_000L;
  private static final long JOIN_TIMEOUT_MILLIS = 5_000L;
  private static final OriginalAudioMetadata DEFAULT_AUDIO_METADATA = OriginalAudioMetadata.of(
    "",
    OriginalAudioMetadata.UNKNOWN,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    OriginalAudioMetadata.UNKNOWN
  );
  private static final DecodedVideoFrame END_OF_VIDEO = new DecodedVideoFrame(null, Long.MIN_VALUE);
  private static final ByteBuffer NO_SAMPLES = ByteBuffer.allocate(0);
  private static final DecodedAudioChunk END_OF_AUDIO = new DecodedAudioChunk(NO_SAMPLES, Long.MIN_VALUE);

  private final GrabberFactory videoGrabberFactory;
  private final @Nullable GrabberFactory audioGrabberFactory;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final BiConsumer<String, Throwable> exceptionHandler;
  private final PlaybackClock clock;
  private final long startMicros;
  private final long audioLeadNanos;
  private final long maxVideoLagNanos;
  private final LongSupplier nanoClock;
  private final BlockingQueue<DecodedVideoFrame> videoQueue;
  private final BlockingQueue<DecodedAudioChunk> audioQueue;
  private final ImagePool imagePool;
  private final VideoFrameCopier frameCopier;
  private final AtomicBoolean running;
  private final List<Thread> threads;

  private volatile long positionMicros;
  private volatile OriginalVideoMetadata videoMetadata;
  private volatile OriginalAudioMetadata audioMetadata;
  private volatile boolean seekable;
  private @Nullable FrameGrabber videoGrabber;

  /**
   * Creates a started frame grabber for a source.
   */
  @FunctionalInterface
  interface GrabberFactory {
    /**
     * Creates and starts the grabber.
     *
     * @return the started grabber
     * @throws FrameGrabber.Exception if the source cannot be opened
     */
    FrameGrabber create() throws FrameGrabber.Exception;
  }

  /**
   * Constructs a new session. Nothing is opened until {@link #open()} or {@link #start()} is called.
   *
   * @param videoGrabberFactory creates the grabber of the video source, which also provides the audio when there is
   *                            no separate audio source
   * @param audioGrabberFactory creates the grabber of a separate audio source, or {@code null} if there is none
   * @param videoCallback       provides the video pipeline
   * @param audioCallback       provides the audio pipeline
   * @param dimensionCallback   provides the size frames are scaled to, if attached
   * @param exceptionHandler    receives decoding and pipeline failures
   * @param startMicros         the position to start at in microseconds
   * @param startPaused         whether the session starts paused
   * @param audioLeadNanos      how long before its timestamp an audio chunk is handed to the pipeline, in nanoseconds;
   *                            players pass {@link #AUDIO_LEAD_NANOS}
   * @param maxVideoLagNanos    how late a frame may be before it is dropped, in nanoseconds; players pass
   *                            {@link #MAX_VIDEO_LAG_NANOS}, and {@link Long#MAX_VALUE} never drops a frame
   * @param nanoClock           the clock media is scheduled with, in nanoseconds; players pass
   *                            {@link System#nanoTime()}, and tests pass a clock they control
   */
  PlaybackSession(
    final GrabberFactory videoGrabberFactory,
    final @Nullable GrabberFactory audioGrabberFactory,
    final VideoAttachableCallback videoCallback,
    final AudioAttachableCallback audioCallback,
    final DimensionAttachableCallback dimensionCallback,
    final BiConsumer<String, Throwable> exceptionHandler,
    final long startMicros,
    final boolean startPaused,
    final long audioLeadNanos,
    final long maxVideoLagNanos,
    final LongSupplier nanoClock
  ) {
    this.videoGrabberFactory = videoGrabberFactory;
    this.audioGrabberFactory = audioGrabberFactory;
    this.videoCallback = videoCallback;
    this.audioCallback = audioCallback;
    this.exceptionHandler = exceptionHandler;
    this.clock = new PlaybackClock(nanoClock);
    this.startMicros = startMicros;
    this.audioLeadNanos = audioLeadNanos;
    this.maxVideoLagNanos = maxVideoLagNanos;
    this.nanoClock = nanoClock;
    this.videoQueue = new ArrayBlockingQueue<>(VIDEO_QUEUE_CAPACITY);
    this.audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    this.imagePool = new ImagePool(IMAGE_POOL_CAPACITY);
    this.frameCopier = new VideoFrameCopier(this.imagePool, dimensionCallback);
    this.running = new AtomicBoolean(true);
    this.threads = new ArrayList<>();
    this.positionMicros = startMicros;
    this.videoMetadata = OriginalVideoMetadata.EMPTY;
    this.audioMetadata = DEFAULT_AUDIO_METADATA;
    if (startPaused) {
      this.clock.pause();
    }
  }

  /**
   * Opens the video source without starting any thread, so a player can find out whether the source can be played
   * before it stops the playback it replaces. Also remembers whether the media can be seeked, which is the case when
   * its length is known; live streams and cameras report no length.
   *
   * @throws FrameGrabber.Exception if the video grabber cannot be created
   */
  void open() throws FrameGrabber.Exception {
    final FrameGrabber grabber = this.videoGrabberFactory.create();
    final long length = grabber.getLengthInTime();
    this.seekable = length > 0;
    this.videoGrabber = grabber;
  }

  /**
   * Starts the decoding and rendering threads, opening the video source first unless {@link #open()} did already.
   * The video grabber is created synchronously, so errors such as a missing file are reported immediately.
   *
   * @throws FrameGrabber.Exception if the video grabber cannot be created
   */
  void start() throws FrameGrabber.Exception {
    if (this.videoGrabber == null) {
      this.open();
    }
    this.startThreads();
  }

  /**
   * Starts the decoding and rendering threads of a session that {@link #open()} opened.
   *
   * @throws IllegalStateException if the session was not opened
   */
  void startThreads() {
    final FrameGrabber grabber = this.videoGrabber;
    if (grabber == null) {
      throw new IllegalStateException("The session must be opened first");
    }
    final GrabberFactory audioFactory = this.audioGrabberFactory;
    final boolean separateAudio = audioFactory != null;
    final Thread videoDecoder = this.createThread("mcav-decode-video", () -> this.decode(grabber, true, !separateAudio));
    this.threads.add(videoDecoder);
    if (audioFactory != null) {
      final Thread audioDecoder = this.createThread("mcav-decode-audio", () -> this.decodeSeparateAudio(audioFactory));
      this.threads.add(audioDecoder);
    }
    final Thread videoRenderer = this.createThread("mcav-render-video", this::renderVideo);
    final Thread audioRenderer = this.createThread("mcav-render-audio", this::renderAudio);
    this.threads.add(videoRenderer);
    this.threads.add(audioRenderer);
    for (final Thread thread : this.threads) {
      thread.start();
    }
  }

  private Thread createThread(final String name, final Runnable task) {
    final Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private void decodeSeparateAudio(final GrabberFactory factory) {
    final FrameGrabber grabber;
    try {
      grabber = factory.create();
    } catch (final FrameGrabber.Exception exception) {
      this.report("Failed to start the audio source", exception);
      signalEnd(this.audioQueue, END_OF_AUDIO);
      return;
    }
    this.decode(grabber, false, true);
  }

  /**
   * Pulls frames out of a started grabber until the media ends or the session stops, then closes the grabber and
   * tells the renderers of the decoded streams that no more media follows.
   */
  private void decode(final FrameGrabber grabber, final boolean wantVideo, final boolean wantAudio) {
    try {
      this.decodeFrames(grabber, wantVideo, wantAudio);
    } catch (final FrameGrabber.Exception exception) {
      this.report("Failed to decode media", exception);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    } catch (final RuntimeException exception) {
      this.report("Unexpected error while decoding media", exception);
    } finally {
      closeQuietly(grabber);
      if (wantVideo) {
        this.frameCopier.release();
        signalEnd(this.videoQueue, END_OF_VIDEO);
      }
      if (wantAudio) {
        signalEnd(this.audioQueue, END_OF_AUDIO);
      }
    }
  }

  /**
   * Decodes every frame. Video frames get their timestamps from {@link VideoTimestamps}, because some decoders, such
   * as the video reader of OpenCV, stamp every frame with zero.
   */
  private void decodeFrames(final FrameGrabber grabber, final boolean wantVideo, final boolean wantAudio)
    throws FrameGrabber.Exception, InterruptedException {
    this.seekToStart(grabber);
    final OriginalVideoMetadata decodedVideoMetadata = createVideoMetadata(grabber);
    final double frameRate = grabber.getFrameRate();
    final long length = grabber.getLengthInTime();
    final VideoTimestamps timestamps = new VideoTimestamps(frameRate, length <= 0, this.nanoClock);
    final OriginalAudioMetadata decodedAudioMetadata = createAudioMetadata(grabber);
    Frame frame = grab(grabber, wantVideo, wantAudio);
    while (frame != null && this.running.get()) {
      if (wantVideo && hasImage(frame)) {
        this.enqueueVideo(frame, decodedVideoMetadata, timestamps);
      }
      if (wantAudio && hasSamples(frame)) {
        this.enqueueAudio(frame, decodedAudioMetadata);
      }
      frame = grab(grabber, wantVideo, wantAudio);
    }
  }

  /**
   * Moves a grabber to the start position of the session. A source that refuses to seek is played from where it
   * is, which is better than not playing it at all; the failure is reported.
   */
  private void seekToStart(final FrameGrabber grabber) {
    if (this.startMicros <= 0) {
      return;
    }
    try {
      grabber.setTimestamp(this.startMicros);
    } catch (final FrameGrabber.Exception exception) {
      this.report("Failed to seek, playing from the current position instead", exception);
    }
  }

  private static boolean hasImage(final Frame frame) {
    final Buffer[] image = frame.image;
    return image != null && image.length > 0;
  }

  private static boolean hasSamples(final Frame frame) {
    final Buffer[] samples = frame.samples;
    return samples != null && samples.length > 0;
  }

  /**
   * Grabs the next frame. FFmpeg grabbers that decode only one stream skip the other stream entirely.
   */
  private static @Nullable Frame grab(final FrameGrabber grabber, final boolean wantVideo, final boolean wantAudio)
    throws FrameGrabber.Exception {
    if (grabber instanceof final FFmpegFrameGrabber ffmpeg && wantVideo != wantAudio) {
      return wantVideo ? ffmpeg.grabImage() : ffmpeg.grabSamples();
    }
    return grabber.grab();
  }

  /**
   * Describes the video track of a grabber. Sources without a video track, such as audio files, report no picture
   * size and get the empty metadata instead.
   */
  private static OriginalVideoMetadata createVideoMetadata(final FrameGrabber grabber) {
    final int width = grabber.getImageWidth();
    final int height = grabber.getImageHeight();
    final int smallerSide = Math.min(width, height);
    if (smallerSide <= 0) {
      return OriginalVideoMetadata.EMPTY;
    }
    final int bitrate = grabber.getVideoBitrate();
    final float frameRate = (float) grabber.getFrameRate();
    return OriginalVideoMetadata.of(width, height, bitrate, frameRate);
  }

  /**
   * Describes the audio track of a grabber. Sources without an audio track get the metadata of the output format
   * every grabber resamples to.
   */
  private static OriginalAudioMetadata createAudioMetadata(final FrameGrabber grabber) {
    final int sampleRate = grabber.getSampleRate();
    final int channels = grabber.getAudioChannels();
    final int smaller = Math.min(sampleRate, channels);
    if (smaller <= 0) {
      return DEFAULT_AUDIO_METADATA;
    }
    final String codec = grabber.getAudioCodecName();
    final String codecName = Objects.requireNonNullElse(codec, "");
    final int bitrate = grabber.getAudioBitrate();
    final int format = grabber.getSampleFormat();
    return OriginalAudioMetadata.of(codecName, bitrate, sampleRate, channels, format);
  }

  /**
   * Queues a frame for the video renderer. The picture is only copied if a pipeline wants it; otherwise the frame
   * travels without it, so the renderer still keeps time.
   */
  private void enqueueVideo(final Frame frame, final OriginalVideoMetadata metadata, final VideoTimestamps timestamps)
    throws InterruptedException {
    final Buffer plane = frame.image[0];
    if (!(plane instanceof ByteBuffer)) {
      return;
    }
    final VideoPipelineStep step = this.videoCallback.retrieve();
    final boolean empty = step.isNoOp();
    final MatImageBuffer image = empty ? null : this.frameCopier.copy(frame);
    final long timestamp = timestamps.next(frame.timestamp);
    final DecodedVideoFrame decoded = new DecodedVideoFrame(image, timestamp);
    this.videoMetadata = metadata;
    try {
      this.videoQueue.put(decoded);
    } catch (final InterruptedException exception) {
      releaseImage(decoded);
      throw exception;
    }
  }

  private void enqueueAudio(final Frame frame, final OriginalAudioMetadata metadata) throws InterruptedException {
    final Buffer samples = frame.samples[0];
    if (!(samples instanceof final ShortBuffer shortSamples)) {
      return;
    }
    final ByteBuffer bytes = copySamples(shortSamples);
    final DecodedAudioChunk chunk = new DecodedAudioChunk(bytes, frame.timestamp);
    this.audioMetadata = metadata;
    this.audioQueue.put(chunk);
  }

  private static ByteBuffer copySamples(final ShortBuffer samples) {
    final ShortBuffer view = samples.duplicate();
    view.rewind();
    final int count = view.remaining();
    final ByteBuffer bytes = ByteBuffer.allocate(count * Short.BYTES);
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    final ShortBuffer target = bytes.asShortBuffer();
    target.put(view);
    return bytes;
  }

  /**
   * Tells the renderer of a queue that no more media follows. When the session is stopping the put is interrupted,
   * which is fine, because stopping hands every renderer its end marker itself.
   */
  private static <T extends @NonNull Object> void signalEnd(final BlockingQueue<T> queue, final T marker) {
    try {
      queue.put(marker);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  private void renderVideo() {
    try {
      DecodedVideoFrame frame = this.videoQueue.take();
      while (frame != END_OF_VIDEO) {
        this.renderVideoFrame(frame);
        frame = this.videoQueue.take();
      }
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    } finally {
      // a decoder that raced the end may have queued one more frame
      final List<DecodedVideoFrame> leftovers = new ArrayList<>();
      this.videoQueue.drainTo(leftovers);
      leftovers.forEach(PlaybackSession::releaseImage);
      this.imagePool.close();
    }
  }

  /**
   * Renders a frame and hands its picture back to the pool, whether the frame was shown, dropped, or interrupted.
   */
  private void renderVideoFrame(final DecodedVideoFrame frame) throws InterruptedException {
    final MatImageBuffer image = frame.getImage();
    try {
      this.presentVideoFrame(frame, image);
    } finally {
      if (image != null) {
        this.imagePool.recycle(image);
      }
    }
  }

  /**
   * Waits until the frame is due and runs it through the pipeline, unless it is too late or nobody watches.
   */
  private void presentVideoFrame(final DecodedVideoFrame frame, final @Nullable MatImageBuffer image) throws InterruptedException {
    final long timestamp = frame.getTimestampMicros();
    final long lateNanos = this.awaitDue(timestamp, 0L);
    if (lateNanos > this.maxVideoLagNanos) {
      return;
    }
    this.positionMicros = timestamp;
    final VideoPipelineStep step = this.videoCallback.retrieve();
    final boolean empty = step.isNoOp();
    if (image == null || empty) {
      return;
    }
    this.runVideoPipeline(step, image);
  }

  private void runVideoPipeline(final VideoPipelineStep first, final MatImageBuffer image) {
    final OriginalVideoMetadata metadata = this.videoMetadata;
    try {
      first.processAll(image, metadata);
    } catch (final RuntimeException | Error exception) {
      // filters are user code, and native filters can throw a LinkageError, so any failure is reported and playback
      // goes on; only a virtual machine error ends the thread, because reporting it would hide it
      ThrowableUtils.throwIfFatal(exception);
      this.report("Video filter failed", exception);
    }
  }

  private static void releaseImage(final DecodedVideoFrame frame) {
    final MatImageBuffer image = frame.getImage();
    if (image != null) {
      image.release();
    }
  }

  private void renderAudio() {
    try {
      DecodedAudioChunk chunk = this.audioQueue.take();
      while (chunk != END_OF_AUDIO) {
        this.renderAudioChunk(chunk);
        chunk = this.audioQueue.take();
      }
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
    }
  }

  private void renderAudioChunk(final DecodedAudioChunk chunk) throws InterruptedException {
    final long timestamp = chunk.getTimestampMicros();
    this.awaitDue(timestamp, this.audioLeadNanos);
    final AudioPipelineStep step = this.audioCallback.retrieve();
    final boolean empty = step.isNoOp();
    if (empty) {
      return;
    }
    final ByteBuffer samples = chunk.getSamples();
    this.runAudioPipeline(step, samples);
  }

  private void runAudioPipeline(final AudioPipelineStep first, final ByteBuffer samples) {
    final OriginalAudioMetadata metadata = this.audioMetadata;
    try {
      first.processAll(samples, metadata);
    } catch (final RuntimeException | Error exception) {
      // filters are user code, and native filters can throw a LinkageError, so any failure is reported and playback
      // goes on; only a virtual machine error ends the thread, because reporting it would hide it
      ThrowableUtils.throwIfFatal(exception);
      this.report("Audio filter failed", exception);
    }
  }

  /**
   * Blocks until media with the given timestamp is due, minus the lead, and keeps waiting while the clock is
   * paused.
   *
   * @return how late the media is in nanoseconds, zero if it is on time
   * @throws InterruptedException if the session stops while waiting
   */
  private long awaitDue(final long timestampMicros, final long leadNanos) throws InterruptedException {
    while (true) {
      if (this.clock.isPaused()) {
        this.clock.awaitResume(PAUSE_POLL_MILLIS);
        continue;
      }
      final long dueNanos = this.clock.dueAt(timestampMicros);
      final long startNanos = dueNanos - leadNanos;
      final long now = this.nanoClock.getAsLong();
      final long remaining = startNanos - now;
      if (remaining <= 0) {
        return -remaining;
      }
      final long sleep = Math.min(remaining, MAX_SLEEP_NANOS);
      LockSupport.parkNanos(sleep);
      if (Thread.interrupted()) {
        throw new InterruptedException("Interrupted while waiting for media to be due");
      }
    }
  }

  /**
   * Pauses the clock, so the renderers stop at their next frame.
   */
  void pause() {
    this.clock.pause();
  }

  /**
   * Resumes the clock and shifts the schedule by the time the session was paused.
   */
  void resume() {
    this.clock.resume();
  }

  /**
   * Checks whether the session is paused.
   *
   * @return true if paused
   */
  boolean isPaused() {
    return this.clock.isPaused();
  }

  /**
   * Checks whether the media of the session can be seeked, which {@link #open()} found out from its length.
   *
   * @return true for media of a known length, false for live streams and cameras, and before the session is opened
   */
  boolean isSeekable() {
    return this.seekable;
  }

  /**
   * Gets the timestamp of the last rendered frame.
   *
   * @return the position in microseconds
   */
  long getPositionMicros() {
    return this.positionMicros;
  }

  /**
   * Checks whether the session is still playing, which is the case until it is stopped or all of its threads
   * finished.
   *
   * @return true if the session is playing
   */
  boolean isActive() {
    if (!this.running.get()) {
      return false;
    }
    for (final Thread thread : this.threads) {
      if (thread.isAlive()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Stops the session and waits for its threads to exit. Calling this method more than once has no effect, and it
   * may be called from a pipeline running on one of the threads of the session.
   *
   * <p>Threads are interrupted, but a decoder stuck in native code only notices at its next frame, so this method
   * blocks for up to five seconds per thread in the worst case.
   */
  void stop() {
    final boolean wasRunning = this.running.getAndSet(false);
    if (!wasRunning) {
      return;
    }

    final Thread currentThread = Thread.currentThread();
    this.interruptThreadsExcept(currentThread);
    // a renderer that stopped the session itself is not interrupted, so it finishes through its end marker
    replaceWithEndMarker(this.videoQueue, END_OF_VIDEO, PlaybackSession::releaseImage);
    replaceWithEndMarker(this.audioQueue, END_OF_AUDIO, PlaybackSession::discardChunk);
    this.joinThreadsExcept(currentThread);
  }

  private void interruptThreadsExcept(final Thread excluded) {
    for (final Thread thread : this.threads) {
      final boolean isExcluded = thread.equals(excluded);
      if (!isExcluded) {
        thread.interrupt();
      }
    }
  }

  /**
   * Waits for every thread of the session but the excluded one to exit. Waiting ends early when the waiting thread is
   * interrupted, which keeps its interrupt.
   */
  private void joinThreadsExcept(final Thread excluded) {
    for (final Thread thread : this.threads) {
      final boolean isExcluded = thread.equals(excluded);
      if (isExcluded) {
        continue;
      }
      try {
        thread.join(JOIN_TIMEOUT_MILLIS);
      } catch (final InterruptedException exception) {
        final Thread waiting = Thread.currentThread();
        waiting.interrupt();
        return;
      }
    }
  }

  private static void discardChunk(final DecodedAudioChunk chunk) {
    // audio chunks live on the heap, so the garbage collector takes care of them
  }

  /**
   * Replaces everything in a queue with the end marker. A decoder that is still running may refill the queue
   * between draining and offering, so the queue is drained again until the marker fits. Every removed element is
   * handed to a consumer, so it can release its resources.
   *
   * @param queue     the queue
   * @param marker    the end marker
   * @param discarded receives every element that was removed from the queue
   * @param <T>       the type of the queued elements
   */
  @VisibleForTesting
  static <T extends @NonNull Object> void replaceWithEndMarker(
    final BlockingQueue<T> queue,
    final T marker,
    final Consumer<? super T> discarded
  ) {
    final List<T> removed = new ArrayList<>();
    do {
      queue.drainTo(removed);
    } while (!queue.offer(marker));
    removed.forEach(discarded);
  }

  private void report(final String message, final Throwable error) {
    if (!this.running.get()) {
      return;
    }
    this.exceptionHandler.accept(message, error);
  }

  private static void closeQuietly(final FrameGrabber grabber) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception exception) {
      // the grabber is being discarded, nothing can be done about a failed close
    }
  }
}
