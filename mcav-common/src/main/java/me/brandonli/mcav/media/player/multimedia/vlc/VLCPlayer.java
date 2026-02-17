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
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.natives.ByteUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.AudioApi;
import uk.co.caprica.vlcj.player.base.MediaApi;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.VideoSurfaceApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import static java.util.Objects.requireNonNull;

/**
 * A VLCJ-based video player that implements the {@link VideoPlayerMultiplexer} interface.
 * <p>
 * When separate video and audio sources are provided, two independent media players are used
 * to avoid VLC pipeline reconfiguration issues with the slave API. The video player runs with
 * {@code :no-audio} and the audio player runs with {@code :no-video}. A periodic sync task
 * keeps the video player aligned to the audio player, which serves as the master clock.
 * <p>
 * When a single combined source is provided, only one media player is used.
 */
public final class VLCPlayer implements VideoPlayerMultiplexer {

  private static final String[] LIBVLC_INIT_ARGS = {};
  private static final long DRIFT_THRESHOLD_MS = 30;
  private static final long DRIFT_HARD_SEEK_MS = 500;
  private static final long SYNC_INTERVAL_MS = 500;

  private final DimensionAttachableCallback dimensionAttachableCallback;
  private final VideoAttachableCallback videoAttachableCallback;
  private final AudioAttachableCallback audioAttachableCallback;
  private final MediaPlayerFactory factory;
  private final EmbeddedMediaPlayer videoPlayer;
  private final AtomicBoolean running;
  private final String[] args;
  private final Lock lock;

  private final ThreadPoolExecutor videoProcessingExecutor;
  private final ThreadPoolExecutor audioProcessingExecutor;

  private @Nullable volatile EmbeddedMediaPlayer audioPlayer;
  private @Nullable volatile ScheduledExecutorService syncExecutor;
  private @Nullable volatile CallbackVideoSurface pinnedVideoSurface;
  private @Nullable volatile BufferCallback pinnedBufferCallback;
  private @Nullable volatile VideoCallback pinnedVideoCallback;
  private @Nullable volatile AudioCallback pinnedAudioCallback;
  private volatile boolean dualPlayerMode;

  private volatile BiConsumer<String, Throwable> exceptionHandler;

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
    this.videoPlayer = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
    this.lock = new ReentrantLock();
    this.running = new AtomicBoolean(false);
    this.videoProcessingExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(2), new ThreadPoolExecutor.DiscardOldestPolicy());
    this.audioProcessingExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    this.args = args;
    this.dualPlayerMode = false;
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
      this.running.set(true);
      this.dualPlayerMode = true;

      this.addVideoCallbacks(video);

      // Video player — no audio decoding
      final MediaApi videoMedia = this.videoPlayer.media();
      final String videoResource = video.getResource();
      videoMedia.prepare(videoResource, this.appendArg(":no-audio"));

      // Audio player — no video decoding, with audio callback
      final EmbeddedMediaPlayer ap = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
      this.audioPlayer = ap;
      this.addAudioCallbacks(audio, ap);
      final String audioResource = audio.getResource();
      ap.media().prepare(audioResource, this.appendArg(":no-video"));

      // Start both together
      this.videoPlayer.controls().play();
      ap.controls().play();

      // Start periodic sync (audio is master clock)
      this.startSyncTask();

      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(true);
      this.dualPlayerMode = false;

      this.addVideoCallbacks(combined);
      this.addAudioCallbacks(combined, this.videoPlayer);

      final MediaApi mediaApi = this.videoPlayer.media();
      final String resource = combined.getResource();
      if (this.args != null && this.args.length > 0) {
        mediaApi.play(resource, this.args);
      } else {
        mediaApi.play(resource);
      }
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

  private String[] appendArg(final String extra) {
    if (this.args != null && this.args.length > 0) {
      final String[] combined = new String[this.args.length + 1];
      System.arraycopy(this.args, 0, combined, 0, this.args.length);
      combined[this.args.length] = extra;
      return combined;
    }
    return new String[]{extra};
  }

  private void addVideoCallbacks(final Source video) {
    final OriginalVideoMetadata videoMetadata = MetadataUtils.parseVideoMetadata(video);
    final VideoSurfaceApi surfaceApi = this.videoPlayer.videoSurface();
    final uk.co.caprica.vlcj.factory.VideoSurfaceApi videoSurfaceApi = this.factory.videoSurfaces();

    final VideoPipelineStep videoPipeline = this.videoAttachableCallback.retrieve();
    this.pinnedBufferCallback = new BufferCallback(videoMetadata);
    this.pinnedVideoCallback = new VideoCallback(videoPipeline, videoMetadata);
    this.pinnedVideoSurface = videoSurfaceApi.newVideoSurface(
            this.pinnedBufferCallback, this.pinnedVideoCallback, true
    );
    surfaceApi.set(this.pinnedVideoSurface);
  }

  private void addAudioCallbacks(final Source audio, final EmbeddedMediaPlayer target) {
    final OriginalAudioMetadata audioMetadata = MetadataUtils.parseAudioMetadata(audio);
    final AudioApi audioApi = target.audio();

    final AudioPipelineStep audioPipeline = this.audioAttachableCallback.retrieve();
    this.pinnedAudioCallback = new AudioCallback(audioPipeline, audioMetadata);
    audioApi.callback("S16N", 48000, 2, requireNonNull(this.pinnedAudioCallback));
  }

  private void startSyncTask() {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
      final Thread t = new Thread(r, "vlc-sync");
      t.setDaemon(true);
      return t;
    });
    this.syncExecutor = executor;
    executor.scheduleAtFixedRate(() -> {
      if (!this.running.get()) {
        return;
      }
      final EmbeddedMediaPlayer ap = this.audioPlayer;
      if (ap == null) {
        return;
      }
      try {
        final long audioTime = ap.status().time();
        final long videoTime = this.videoPlayer.status().time();
        final long drift = videoTime - audioTime;
        final long absDrift = Math.abs(drift);

        if (absDrift > DRIFT_HARD_SEEK_MS) {
          // Drift is too large to correct gradually, hard seek
          this.videoPlayer.controls().setTime(audioTime);
          this.videoPlayer.controls().setRate(1.0f);
        } else if (absDrift > DRIFT_THRESHOLD_MS) {
          // Video is ahead — slow it down. Video is behind — speed it up.
          final float correction = drift > 0 ? 0.97f : 1.03f;
          this.videoPlayer.controls().setRate(correction);
        } else {
          // Within tolerance, restore normal rate
          this.videoPlayer.controls().setRate(1.0f);
        }
      } catch (final Throwable e) {
        // Player may have been released, ignore
      }
    }, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  private void stopSyncTask() {
    final ScheduledExecutorService executor = this.syncExecutor;
    if (executor != null) {
      executor.shutdownNow();
      this.syncExecutor = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      this.videoPlayer.controls().pause();
      if (this.dualPlayerMode) {
        final EmbeddedMediaPlayer ap = this.audioPlayer;
        if (ap != null) {
          ap.controls().pause();
        }
      }
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.dualPlayerMode) {
        final EmbeddedMediaPlayer ap = this.audioPlayer;
        if (ap != null) {
          // Sync video to audio position before resuming
          final long time = ap.status().time();
          this.videoPlayer.controls().setTime(time);
          ap.controls().start();
        }
      }
      this.videoPlayer.controls().start();
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
      this.videoPlayer.controls().setTime(time);
      if (this.dualPlayerMode) {
        final EmbeddedMediaPlayer ap = this.audioPlayer;
        if (ap != null) {
          ap.controls().setTime(time);
        }
      }
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
      this.stopSyncTask();
      this.videoProcessingExecutor.shutdownNow();
      this.audioProcessingExecutor.shutdownNow();
      final EmbeddedMediaPlayer ap = this.audioPlayer;
      if (ap != null) {
        ap.release();
        this.audioPlayer = null;
      }
      this.videoPlayer.release();
      this.factory.release();
      this.pinnedVideoSurface = null;
      this.pinnedBufferCallback = null;
      this.pinnedVideoCallback = null;
      this.pinnedAudioCallback = null;
      this.dualPlayerMode = false;
      return true;
    });
  }

  private final class BufferCallback extends BufferFormatCallbackAdapter {

    private final RV32BufferFormat format;

    BufferCallback(final OriginalVideoMetadata metadata) {
      final Dimension dimension = VLCPlayer.this.dimensionAttachableCallback.retrieve();
      if (VLCPlayer.this.dimensionAttachableCallback.isAttached()) {
        this.format = new RV32BufferFormat(dimension.getWidth(), dimension.getHeight());
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
      final ThreadPoolExecutor executor = VLCPlayer.this.audioProcessingExecutor;
      if (executor.isShutdown()) {
        return;
      }
      final int bufferSize = sampleCount * BLOCK_SIZE;
      final byte[] bytes = samples.getByteArray(0, bufferSize);
      executor.submit(() -> {
        try {
          final ByteBuffer buffer = ByteBuffer.wrap(bytes);
          final ByteBuffer converted = ByteUtils.clampNativeBufferToLittleEndian(buffer);
          AudioPipelineStep current = this.step;
          while (current != null) {
            current.process(converted, this.metadata);
            current = current.next();
          }
        } catch (final Throwable e) {
          final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
          VLCPlayer.this.exceptionHandler.accept(msg, e);
        }
      });
    }
  }

  private final class VideoCallback extends RenderCallbackAdapter {

    private final VideoPipelineStep step;
    private final OriginalVideoMetadata metadata;
    private final int width;
    private final int height;

    VideoCallback(final VideoPipelineStep step, final OriginalVideoMetadata metadata) {
      final Dimension dimension = VLCPlayer.this.dimensionAttachableCallback.retrieve();
      if (VLCPlayer.this.dimensionAttachableCallback.isAttached()) {
        this.width = dimension.getWidth();
        this.height = dimension.getHeight();
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
      final ThreadPoolExecutor executor = VLCPlayer.this.videoProcessingExecutor;
      if (executor.isShutdown() || executor.getQueue().size() >= 2) {
        return;
      }
      final int[] copy = buffer.clone();
      executor.submit(() -> {
        try {
          final ImageBuffer image = ImageBuffer.buffer(copy, this.width, this.height);
          VideoPipelineStep current = this.step;
          while (current != null) {
            current.process(image, this.metadata);
            current = current.next();
          }
          image.release();
        } catch (final Throwable e) {
          final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
          VLCPlayer.this.exceptionHandler.accept(msg, e);
        }
      });
    }
  }
}