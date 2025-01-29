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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static java.util.Objects.requireNonNull;

import com.sun.jna.Pointer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.natives.ByteUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.media.MediaSlavePriority;
import uk.co.caprica.vlcj.media.MediaSlaveType;
import uk.co.caprica.vlcj.media.SlaveApi;
import uk.co.caprica.vlcj.player.base.AudioApi;
import uk.co.caprica.vlcj.player.base.ControlsApi;
import uk.co.caprica.vlcj.player.base.MediaApi;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * A VLCJ-based video player that implements the {@link VideoPlayerMultiplexer} interface.
 */
public final class VLCPlayer implements VideoPlayerMultiplexer {

  private static final String[] LIBVLC_INIT_ARGS = { "--reset-plugins-cache" };

  private final DimensionAttachableCallback dimensionAttachableCallback;
  private final VideoAttachableCallback videoAttachableCallback;
  private final AudioAttachableCallback audioAttachableCallback;
  private final MediaPlayerFactory factory;
  private final EmbeddedMediaPlayer player;
  private final AtomicBoolean running;
  private final String[] args;
  private final Lock lock;

  @Nullable private volatile VideoCallback videoCallback;

  @Nullable private volatile AudioCallback audioCallback;

  @Nullable private volatile CallbackVideoSurface videoSurface;

  @Nullable private volatile BufferFormatCallback bufferFormatCallback;

  private BiConsumer<String, Throwable> exceptionHandler;

  /**
   * Constructs a new VLCPlayer instance with the specified command-line arguments.
   *
   * @param args the command-line arguments to pass to the VLC player
   */
  public VLCPlayer(final String[] args) {
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.dimensionAttachableCallback = DimensionAttachableCallback.create();
    this.videoAttachableCallback = VideoAttachableCallback.create();
    this.audioAttachableCallback = AudioAttachableCallback.create();
    this.factory = new MediaPlayerFactory(LIBVLC_INIT_ARGS);
    this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
    this.lock = new ReentrantLock();
    this.running = new AtomicBoolean(false);
    this.args = args;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final Source video, final Source audio) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.prepareMedia(video, audio);
      final MediaApi mediaApi = this.player.media();
      final String audioResource = audio.getResource();
      final URI audioUri = URI.create(audioResource);
      final String result = audioUri.toString();
      final SlaveApi slaveApi = mediaApi.slaves();
      slaveApi.add(MediaSlaveType.AUDIO, MediaSlavePriority.HIGHEST, result);
      this.running.set(true);
      return true;
    });
  }

  private void prepareMedia(final Source video, final Source audio) {
    final MediaApi mediaApi = this.player.media();
    final String videoResource = video.getResource();
    this.addCallbacks(video, audio);
    if (this.args != null && this.args.length > 0) {
      mediaApi.play(videoResource, this.args);
    } else {
      mediaApi.play(videoResource);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.prepareMedia(combined, combined);
      this.running.set(true);
      return true;
    });
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoAttachableCallback;
  }

  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioAttachableCallback;
  }

  @Override
  public DimensionAttachableCallback getDimensionAttachableCallback() {
    return this.dimensionAttachableCallback;
  }

  private void addCallbacks(final Source video, final Source audio) {
    final OriginalAudioMetadata audioMetadata = MetadataUtils.parseAudioMetadata(audio);
    final OriginalVideoMetadata videoMetadata = MetadataUtils.parseVideoMetadata(video);
    final AudioApi audioApi = this.player.audio();
    final VideoSurfaceApi surfaceApi = this.player.videoSurface();
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi videoSurfaceApi = this.factory.videoSurfaces();

    final VideoPipelineStep videoPipeline = this.videoAttachableCallback.retrieve();
    final AudioPipelineStep audioPipeline = this.audioAttachableCallback.retrieve();
    final BufferCallback bufferCallback = new BufferCallback(videoMetadata);
    final VideoCallback videoCallback = new VideoCallback(videoPipeline, videoMetadata);
    final AudioCallback audioCallback = new AudioCallback(audioPipeline, audioMetadata);
    final CallbackVideoSurface videoSurface = videoSurfaceApi.newVideoSurface(bufferCallback, videoCallback, true);

    this.bufferFormatCallback = bufferCallback;
    this.videoCallback = videoCallback;
    this.audioCallback = audioCallback;
    this.videoSurface = videoSurface;

    // audio sample specialization
    // PCM S16LE
    // 2 Channels
    // 48 kHz
    surfaceApi.set(videoSurface);
    audioApi.callback("S16N", 48000, 2, audioCallback);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      final ControlsApi controls = this.player.controls();
      controls.pause();
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> {
      final ControlsApi controls = this.player.controls();
      controls.start();
      this.running.set(true);
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      final ControlsApi controls = this.player.controls();
      controls.setTime(time);
      this.running.set(true);
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      this.player.release();
      this.factory.release();
      return true;
    });
  }

  private final class BufferCallback extends BufferFormatCallbackAdapter {

    private final RV32BufferFormat format;

    BufferCallback(final OriginalVideoMetadata metadata) {
      final DimensionAttachableCallback.Dimension dimension = VLCPlayer.this.dimensionAttachableCallback.retrieve();
      if (VLCPlayer.this.dimensionAttachableCallback.isAttached()) {
        this.format = new RV32BufferFormat(dimension.width(), dimension.height());
        return;
      }
      this.format = new RV32BufferFormat(metadata.getVideoWidth(), metadata.getVideoHeight());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BufferFormat getBufferFormat(final int sourceWidth, final int sourceHeight) {
      return this.format;
    }
  }

  private final class AudioCallback extends AudioCallbackAdapter {

    private static final int BLOCK_SIZE = 4;

    private final AudioPipelineStep step;
    private final OriginalAudioMetadata metadata;

    AudioCallback(final AudioPipelineStep step, final OriginalAudioMetadata metadata) {
      this.step = step;
      this.metadata = metadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void play(final MediaPlayer mediaPlayer, final Pointer samples, final int sampleCount, final long pts) {
      if (!VLCPlayer.this.running.get()) {
        return;
      }
      try {
        final int bufferSize = sampleCount * BLOCK_SIZE;
        final byte[] bytes = samples.getByteArray(0, bufferSize);
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final ByteBuffer converted = ByteUtils.clampNativeBufferToLittleEndian(buffer);
        AudioPipelineStep current = this.step;
        while (current != null) {
          current.process(converted, this.metadata);
          current = current.next();
        }
      } catch (final Exception e) {
        final String msg = e.getMessage();
        requireNonNull(msg);
        VLCPlayer.this.exceptionHandler.accept(msg, e);
      }
    }
  }

  private final class VideoCallback extends RenderCallbackAdapter {

    private final VideoPipelineStep step;
    private final OriginalVideoMetadata metadata;
    private final int width;
    private final int height;

    VideoCallback(final VideoPipelineStep step, final OriginalVideoMetadata metadata) {
      final DimensionAttachableCallback.Dimension dimension = VLCPlayer.this.dimensionAttachableCallback.retrieve();
      if (VLCPlayer.this.dimensionAttachableCallback.isAttached()) {
        this.width = dimension.width();
        this.height = dimension.height();
      } else {
        this.width = metadata.getVideoWidth();
        this.height = metadata.getVideoHeight();
      }
      final int[] buffer = new int[this.width * this.height];
      this.step = step;
      this.metadata = metadata;
      this.setBuffer(buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDisplay(final MediaPlayer mediaPlayer, final int[] buffer) {
      if (!VLCPlayer.this.running.get()) {
        return;
      }
      try {
        final ImageBuffer image = ImageBuffer.buffer(buffer, this.width, this.height);
        VideoPipelineStep current = this.step;
        while (current != null) {
          current.process(image, this.metadata);
          current = current.next();
        }
        image.release();
      } catch (final Exception e) {
        final String msg = e.getMessage();
        requireNonNull(msg);
        VLCPlayer.this.exceptionHandler.accept(msg, e);
      }
    }
  }
}
