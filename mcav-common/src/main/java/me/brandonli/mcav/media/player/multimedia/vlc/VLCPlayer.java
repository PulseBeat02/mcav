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

import com.sun.jna.Pointer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.natives.ByteUtils;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerApi;
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
import uk.co.caprica.vlcj.player.embedded.videosurface.*;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * A VLCJ-based video player that implements the {@link VideoPlayerMultiplexer} interface.
 */
public final class VLCPlayer implements VideoPlayerMultiplexer {

  private final EmbeddedMediaPlayer player;
  private final VideoSurfaceAdapter adapter;
  private final String[] args;
  private final Lock lock;

  @Nullable private volatile VideoCallback videoCallback;

  @Nullable private volatile AudioCallback audioCallback;

  @Nullable private volatile CallbackVideoSurface videoSurface;

  @Nullable private volatile BufferFormatCallback bufferFormatCallback;

  /**
   * Constructs a new VLCPlayer instance with the specified command-line arguments.
   *
   * @param args the command-line arguments to pass to the VLC player
   */
  public VLCPlayer(final String[] args) {
    final MediaPlayerFactory factory = MediaPlayerFactoryProvider.getPlayerFactory();
    final MediaPlayerApi mediaPlayerApi = factory.mediaPlayers();
    this.player = mediaPlayerApi.newEmbeddedMediaPlayer();
    this.adapter = this.getAdapter();
    this.lock = new ReentrantLock();
    this.args = args;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.prepareMedia(audioPipeline, videoPipeline, video, audio);
      final MediaApi mediaApi = this.player.media();
      final String audioResource = audio.getResource();
      final URI audioUri = URI.create(audioResource);
      final String result = audioUri.toString();
      final SlaveApi slaveApi = mediaApi.slaves();
      slaveApi.add(MediaSlaveType.AUDIO, MediaSlavePriority.HIGHEST, result);
      return true;
    });
  }

  private void prepareMedia(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    final MediaApi mediaApi = this.player.media();
    final String videoResource = video.getResource();
    this.addCallbacks(audioPipeline, videoPipeline, video, audio);
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
  public boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.prepareMedia(audioPipeline, videoPipeline, combined, combined);
      return true;
    });
  }

  private void addCallbacks(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    final VideoMetadata videoMetadata = MetadataUtils.parseVideoMetadata(video);
    final VideoSurfaceApi surfaceApi = this.player.videoSurface();
    this.bufferFormatCallback = new BufferCallback();
    this.videoCallback = new VideoCallback(videoPipeline, videoMetadata);
    this.videoSurface = new CallbackVideoSurface(this.bufferFormatCallback, this.videoCallback, true, this.adapter);
    surfaceApi.set(this.videoSurface);

    // audio sample specialization
    // PCM S16LE
    // 2 Channels
    // 48 kHz
    final AudioMetadata audioMetadata = MetadataUtils.parseAudioMetadata(audio);
    final AudioApi audioApi = this.player.audio();
    this.audioCallback = new AudioCallback(audioPipeline, audioMetadata);
    audioApi.callback("S16N", 48000, 2, this.audioCallback);
  }

  private VideoSurfaceAdapter getAdapter(@UnderInitialization VLCPlayer this) {
    return switch (OSUtils.getOS()) {
      case WINDOWS -> new WindowsVideoSurfaceAdapter();
      case MAC -> new OsxVideoSurfaceAdapter();
      default -> new LinuxVideoSurfaceAdapter();
    };
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
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
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) {
    return LockUtils.executeWithLock(this.lock, () -> {
      final ControlsApi controls = this.player.controls();
      controls.setTime(time);
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.player.release();
      return true;
    });
  }

  private static final class BufferCallback implements BufferFormatCallback {

    BufferCallback() {
      // no-op
    }

    @Override
    public BufferFormat getBufferFormat(final int sourceWidth, final int sourceHeight) {
      return new RV32BufferFormat(sourceWidth, sourceHeight);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void newFormatSize(final int bufferWidth, final int bufferHeight, final int displayWidth, final int displayHeight) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void allocatedBuffers(final ByteBuffer[] buffers) {}
  }

  private static final class AudioCallback extends AudioCallbackAdapter {

    private static final int BLOCK_SIZE = 4;

    private final AudioPipelineStep step;
    private final AudioMetadata metadata;

    AudioCallback(final AudioPipelineStep step, final AudioMetadata metadata) {
      this.step = step;
      this.metadata = metadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void play(final MediaPlayer mediaPlayer, final Pointer samples, final int sampleCount, final long pts) {
      final int bufferSize = sampleCount * BLOCK_SIZE;
      final byte[] bytes = samples.getByteArray(0, bufferSize);
      final ByteBuffer buffer = ByteBuffer.wrap(bytes);
      final ByteBuffer converted = ByteUtils.clampNativeBufferToLittleEndian(buffer);
      AudioPipelineStep current = this.step;
      while (current != null) {
        current.process(converted, this.metadata);
        current = current.next();
      }
    }
  }

  private static final class VideoCallback implements RenderCallback {

    private final VideoPipelineStep step;
    private final VideoMetadata metadata;

    VideoCallback(final VideoPipelineStep step, final VideoMetadata metadata) {
      this.step = step;
      this.metadata = metadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void lock(final MediaPlayer mediaPlayer) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void display(
      final MediaPlayer mediaPlayer,
      final ByteBuffer[] nativeBuffers,
      final BufferFormat bufferFormat,
      final int displayWidth,
      final int displayHeight
    ) {
      final int width = bufferFormat.getWidth();
      final int height = bufferFormat.getHeight();
      final int bitrate = this.metadata.getVideoBitrate();
      final float frameRate = this.metadata.getVideoFrameRate();
      final VideoMetadata updated = VideoMetadata.of(width, height, bitrate, frameRate);
      final ByteBuffer first = nativeBuffers[0];
      final IntBuffer intBuffer = first.asIntBuffer();
      final int[] buffer = new int[width * height];
      intBuffer.get(buffer, 0, width * height);

      final ImageBuffer image = ImageBuffer.buffer(buffer, width, height);
      VideoPipelineStep current = this.step;
      while (current != null) {
        current.process(image, updated);
        current = current.next();
      }

      image.release();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlock(final MediaPlayer mediaPlayer) {}
  }
}
