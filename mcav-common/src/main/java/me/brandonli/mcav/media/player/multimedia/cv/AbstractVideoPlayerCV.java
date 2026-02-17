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

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.natives.ByteUtils;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

import javax.sound.sampled.LineUnavailableException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import static java.util.Objects.requireNonNull;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.swscale.SWS_POINT;

/**
 * Abstract implementation of a video player that uses JavaCV for multimedia processing.
 */
public abstract class AbstractVideoPlayerCV implements VideoPlayerCV {

  private static final int MAX_VIDEO_QUEUE_SIZE = 2;
  private static final long MAX_DESYNC_NS = 100_000_000L;
  private static final long RESYNC_THRESHOLD_NS = 100_000_000L;
  private static final int MAX_CONSECUTIVE_DROPS = 10;

  private final DimensionAttachableCallback dimensionCallback;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;

  @Nullable private volatile ExecutorService playerThread;

  @Nullable private volatile ThreadPoolExecutor audioProcessor;

  @Nullable private volatile ThreadPoolExecutor videoProcessor;

  @Nullable private volatile FrameGrabber grabber;

  @Nullable private volatile Source currentSource;

  @Nullable private volatile Long seekPosition;

  private int consecutiveDrops;
  private long lastResyncNs;
  private final AtomicLong audioPlaybackPtsUs;

  private final AtomicBoolean running;
  private final Lock lock;

  private volatile BiConsumer<String, Throwable> exceptionHandler;
  private volatile long firstFramePtsUs;
  private volatile long playStartNs;

  /**
   * Constructs a new AbstractVideoPlayerCV instance.
   */
  public AbstractVideoPlayerCV() {
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.videoCallback = VideoAttachableCallback.create();
    this.audioCallback = AudioAttachableCallback.create();
    this.running = new AtomicBoolean(false);
    this.audioPlaybackPtsUs = new AtomicLong(0);
    this.lock = new ReentrantLock();
    this.consecutiveDrops = 0;
    this.lastResyncNs = 0;
    this.firstFramePtsUs = -1;
    this.playStartNs = -1;
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
      try {
        this.stop();
        this.grabber = this.createGrabber(video);
        this.currentSource = video;
        this.startPlaybackWithSeparateAudio(audio);
        return true;
      } catch (final Throwable e) {
        this.stop();
        final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        this.exceptionHandler.accept(msg, e);
        throw new PlayerException(msg, e);
      }
    });
  }

  private void startPlaybackWithSeparateAudio(final Source audioSource) throws LineUnavailableException {
    this.running.set(true);

    this.audioProcessor = new ThreadPoolExecutor(
      1,
      1,
      0,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    this.videoProcessor = new ThreadPoolExecutor(
      1,
      1,
      0,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    final ExecutorService service = Executors.newSingleThreadExecutor();
    service.submit(() -> this.playbackWithSeparateAudio(audioSource));
    this.playerThread = service;
  }

  private void playbackWithSeparateAudio(final Source audioSource) {
    final FrameGrabber videoGrabber = requireNonNull(this.grabber);
    final ThreadPoolExecutor audioExec = requireNonNull(this.audioProcessor);
    final ThreadPoolExecutor videoExec = requireNonNull(this.videoProcessor);
    FrameGrabber audioGrabber = null;
    boolean separateAudioSource = !audioSource.getResource().equals(videoGrabber.getFormat());
    if (separateAudioSource) {
      try {
        audioGrabber = this.createAudioGrabber(audioSource);
      } catch (final Exception e) {
        final String raw = e.getMessage();
        final Class<?> clazz = e.getClass();
        final String msg = raw != null ? raw : clazz.getName();
        this.exceptionHandler.accept(msg, e);
        separateAudioSource = false;
      }
    }

    final float detectedFps = (float) videoGrabber.getFrameRate();
    final float targetFps = detectedFps > 0 ? detectedFps : 30f;
    final OriginalVideoMetadata videoMeta = OriginalVideoMetadata.of(
      videoGrabber.getImageWidth(),
      videoGrabber.getImageHeight(),
      videoGrabber.getVideoBitrate(),
      targetFps
    );

    final OriginalAudioMetadata audioMeta;
    if (separateAudioSource && audioGrabber != null) {
      audioMeta = OriginalAudioMetadata.of(
        audioGrabber.getAudioCodecName(),
        audioGrabber.getAudioBitrate(),
        audioGrabber.getSampleRate(),
        audioGrabber.getAudioChannels(),
        audioGrabber.getSampleFormat()
      );
    } else {
      audioMeta = OriginalAudioMetadata.of(
        videoGrabber.getAudioCodecName(),
        videoGrabber.getAudioBitrate(),
        videoGrabber.getSampleRate(),
        videoGrabber.getAudioChannels(),
        videoGrabber.getSampleFormat()
      );
    }

    try {
      if (separateAudioSource) {
        requireNonNull(audioGrabber);
        this.playbackSeparateSources(videoGrabber, audioGrabber, videoMeta, audioMeta, audioExec, videoExec);
      } else {
        this.playbackCombinedSource(videoGrabber, videoMeta, audioMeta, audioExec, videoExec);
      }
    } catch (final FrameGrabber.Exception e) {
      final String raw = e.getMessage();
      final Class<?> clazz = e.getClass();
      final String msg = raw != null ? raw : clazz.getName();
      this.exceptionHandler.accept(msg, e);
    } finally {
      if (audioGrabber != null) {
        try {
          audioGrabber.stop();
          audioGrabber.close();
        } catch (final FrameGrabber.Exception e) {
          final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
          this.exceptionHandler.accept(msg, e);
        }
      }
    }
  }

  private void playbackSeparateSources(
    final FrameGrabber videoGrabber,
    final FrameGrabber audioGrabber,
    final OriginalVideoMetadata videoMeta,
    final OriginalAudioMetadata audioMeta,
    final ThreadPoolExecutor audioExec,
    final ThreadPoolExecutor videoExec
  ) throws FrameGrabber.Exception {
    final ExecutorService audioSourceThread = Executors.newSingleThreadExecutor();
    audioSourceThread.submit(() -> {
      try {
        Frame audioFrame;
        while ((audioFrame = audioGrabber.grab()) != null && this.running.get()) {
          if (audioFrame.samples != null) {
            final Frame copy = audioFrame.clone();
            audioExec.submit(() -> this.processAudioFrame(copy, audioMeta));
          }
        }
      } catch (final FrameGrabber.Exception e) {
        final String raw = e.getMessage();
        final Class<?> clazz = e.getClass();
        final String msg = raw != null ? raw : clazz.getName();
        this.exceptionHandler.accept(msg, e);
      }
    });

    Frame videoFrame;
    while ((videoFrame = videoGrabber.grab()) != null && this.running.get()) {
      if (videoFrame.image != null && this.videoCallback.isAttached()) {
        this.awaitVideoQueueCapacity(videoExec);
        if (!this.running.get()) {
          break;
        }
        final Frame copy = videoFrame.clone();
        videoExec.submit(() -> this.processVideoFrame(copy, videoMeta));
      }
    }

    audioSourceThread.shutdownNow();
  }

  private void playbackCombinedSource(
    final FrameGrabber grabber,
    final OriginalVideoMetadata videoMeta,
    final OriginalAudioMetadata audioMeta,
    final ThreadPoolExecutor audioExec,
    final ThreadPoolExecutor videoExec
  ) throws FrameGrabber.Exception {
    Frame frame;
    while ((frame = grabber.grab()) != null && this.running.get()) {
      if (frame.samples != null) {
        final Frame audioCopy = frame.clone();
        audioExec.submit(() -> this.processAudioFrame(audioCopy, audioMeta));
      }
      if (frame.image != null && this.videoCallback.isAttached()) {
        this.awaitVideoQueueCapacity(videoExec);
        if (!this.running.get()) {
          break;
        }
        final Frame videoCopy = frame.clone();
        videoExec.submit(() -> this.processVideoFrame(videoCopy, videoMeta));
      }
    }
  }

  private FrameGrabber createAudioGrabber(final Source audioSource) throws FrameGrabber.Exception {
    final String resource = audioSource.getResource();
    final FrameGrabber grabber = this.getFrameGrabber(resource);
    grabber.setOption("threads", "auto");
    grabber.setOption("fflags", "fastseek+flush_packets");
    grabber.setOption("flags", "low_delay");
    grabber.setOption("audio_buffer_size", "16384");
    grabber.setOption("thread_queue_size", "16384");
    grabber.setOption("http_persistent", "0");

    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
    grabber.setSampleRate(48000);
    grabber.setAudioChannels(2);

    if (audioSource instanceof final FFmpegDirectSource direct) {
      final String format = direct.getFormat();
      grabber.setFormat(format);
    }

    if (this.seekPosition != null) {
      final long position = requireNonNull(this.seekPosition);
      grabber.setTimestamp(position);
    }

    grabber.start();

    return grabber;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      try {
        this.stop();
        this.grabber = this.createGrabber(combined);
        this.currentSource = combined;
        this.startPlayback();
        return true;
      } catch (final Throwable e) {
        this.stop();
        final String raw = e.getMessage();
        final Class<?> clazz = e.getClass();
        final String msg = raw != null ? raw : clazz.getName();
        this.exceptionHandler.accept(msg, e);
        throw new PlayerException(msg, e);
      }
    });
  }

  private FrameGrabber createGrabber(final Source source) throws FrameGrabber.Exception {
    final String raw = source.getResource();
    final FrameGrabber grabber = this.getFrameGrabber(raw);
    grabber.setOption("tune", "zerolatency");
    grabber.setOption("preset", "ultrafast");
    grabber.setOption("threads", "auto");
    grabber.setOption("thread_type", "slice+frame");
    grabber.setOption("fflags", "fastseek+flush_packets");
    grabber.setOption("flags", "low_delay");
    grabber.setOption("audio_buffer_size", "16384");
    grabber.setOption("reorder_queue_size", "0");
    grabber.setOption("thread_queue_size", "16384");
    grabber.setOption("avoid_negative_ts", "disabled");
    grabber.setOption("rtbufsize", "2048k");
    grabber.setOption("buffer_size", "2048k");
    grabber.setOption("hwaccel", "auto");
    grabber.setOption("http_persistent", "0");

    grabber.setPixelFormat(AV_PIX_FMT_BGR24);
    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
    grabber.setImageScalingFlags(SWS_POINT);

    grabber.setSampleRate(48000);
    grabber.setAudioChannels(2);

    if (this.dimensionCallback.isAttached()) {
      final Dimension dim = this.dimensionCallback.retrieve();
      grabber.setImageWidth(dim.getWidth());
      grabber.setImageHeight(dim.getHeight());
    }

    if (source instanceof final FFmpegDirectSource direct) {
      grabber.setOption("probesize", "32");
      grabber.setOption("analyzeduration", "0");
      final String format = direct.getFormat();
      grabber.setFormat(format);
    }

    if (this.seekPosition != null) {
      final long position = requireNonNull(this.seekPosition);
      grabber.setTimestamp(position);
    }

    grabber.start();

    return grabber;
  }

  private void startPlayback() {
    this.running.set(true);

    this.audioProcessor = new ThreadPoolExecutor(
      1,
      1,
      0,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    this.videoProcessor = new ThreadPoolExecutor(
      1,
      1,
      0,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    final ExecutorService service = Executors.newSingleThreadExecutor();
    service.submit(this::playback);
    this.playerThread = service;
  }

  private void playback() {
    final FrameGrabber grabber = requireNonNull(this.grabber);
    final ThreadPoolExecutor audioExec = requireNonNull(this.audioProcessor);
    final ThreadPoolExecutor videoExec = requireNonNull(this.videoProcessor);
    final OriginalVideoMetadata videoMeta = OriginalVideoMetadata.of(
      grabber.getImageWidth(),
      grabber.getImageHeight(),
      grabber.getVideoBitrate(),
      (float) grabber.getFrameRate()
    );
    final OriginalAudioMetadata audioMeta = OriginalAudioMetadata.of(
      grabber.getAudioCodecName(),
      grabber.getAudioBitrate(),
      grabber.getSampleRate(),
      grabber.getAudioChannels(),
      grabber.getSampleFormat()
    );
    try {
      Frame frame;
      while ((frame = grabber.grab()) != null && this.running.get()) {
        if (frame.samples != null) {
          final Frame audioCopy = frame.clone();
          audioExec.submit(() -> this.processAudioFrame(audioCopy, audioMeta));
        }
        if (frame.image != null && this.videoCallback.isAttached()) {
          this.awaitVideoQueueCapacity(videoExec);
          if (!this.running.get()) {
            break;
          }
          final Frame videoCopy = frame.clone();
          videoExec.submit(() -> this.processVideoFrame(videoCopy, videoMeta));
        }
      }
    } catch (final FrameGrabber.Exception e) {
      final String raw = e.getMessage();
      final Class<?> clazz = e.getClass();
      final String msg = raw != null ? raw : clazz.getName();
      this.exceptionHandler.accept(msg, e);
    }
  }

  private void processAudioFrame(final Frame frame, final OriginalAudioMetadata meta) {
    try (frame) {
      final long ptsUs = frame.timestamp;
      this.audioPlaybackPtsUs.set(ptsUs);
      final ByteBuffer data = ByteUtils.convertAudioSamplesToLittleEndian(frame.samples[0]);
      AudioPipelineStep step = this.audioCallback.retrieve();
      while (step != null) {
        step.process(data, meta);
        step = step.next();
      }
    } catch (final Throwable e) {
      final String raw = e.getMessage();
      final Class<?> clazz = e.getClass();
      final String msg = raw != null ? raw : clazz.getName();
      this.exceptionHandler.accept(msg, e);
    }
  }

  private boolean processVideoFrame(final Frame frame, final OriginalVideoMetadata meta) {
    try (frame) {
      final long ptsUs = frame.timestamp;
      if (this.firstFramePtsUs < 0) {
        this.firstFramePtsUs = ptsUs;
        this.playStartNs = System.nanoTime();
        this.lastResyncNs = this.playStartNs;
      }

      final long elapsedMediaNs = (ptsUs - this.firstFramePtsUs) * 1000L;
      final long targetNs = this.playStartNs + elapsedMediaNs;
      final long nowNs = System.nanoTime();
      long delayNs = targetNs - nowNs;

      if (this.shouldResync(nowNs, delayNs)) {
        this.resynchronize(ptsUs, nowNs);
        final long newElapsedMediaNs = (ptsUs - this.firstFramePtsUs) * 1000L;
        final long newTargetNs = this.playStartNs + newElapsedMediaNs;
        delayNs = newTargetNs - nowNs;
      }

      if (delayNs < -MAX_DESYNC_NS) {
        this.consecutiveDrops++;
        if (this.consecutiveDrops >= MAX_CONSECUTIVE_DROPS) {
          this.resynchronize(ptsUs, nowNs);
          this.consecutiveDrops = 0;
        }
        return true; // Drop frame
      }

      this.consecutiveDrops = 0;

      if (delayNs > 0) {
        if (delayNs > MAX_DESYNC_NS) {
          LockSupport.parkNanos(delayNs - MAX_DESYNC_NS);
          delayNs = MAX_DESYNC_NS;
        }
        final long deadline = System.nanoTime() + delayNs;
        while (System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
      }

      final ByteBuffer data = (ByteBuffer) frame.image[0];
      final ImageBuffer img = ImageBuffer.bytes(data, frame.imageWidth, frame.imageHeight);
      VideoPipelineStep step = this.videoCallback.retrieve();
      while (step != null) {
        step.process(img, meta);
        step = step.next();
      }
      img.release();
    } catch (final Throwable e) {
      final String raw = e.getMessage();
      final Class<?> clazz = e.getClass();
      final String msg = raw != null ? raw : clazz.getName();
      this.exceptionHandler.accept(msg, e);
    }
    return false;
  }

  private boolean shouldResync(final long nowNs, final long delayNs) {
    final long timeSinceResync = nowNs - this.lastResyncNs;
    return timeSinceResync > 5_000_000_000L || Math.abs(delayNs) > RESYNC_THRESHOLD_NS;
  }

  private void resynchronize(final long currentPtsUs, final long nowNs) {
    this.firstFramePtsUs = currentPtsUs;
    this.playStartNs = nowNs;
    this.lastResyncNs = nowNs;
  }

  private void awaitVideoQueueCapacity(final ThreadPoolExecutor videoExec) {
    while (videoExec.getQueue().size() >= MAX_VIDEO_QUEUE_SIZE && this.running.get()) {
      LockSupport.parkNanos(1_000_000L);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.stop();
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> this.currentSource != null && this.start(this.currentSource));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) {
    this.seekPosition = time;
    return this.resume();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.stop();
      return true;
    });
  }

  private void stop() {
    this.running.set(false);
    this.firstFramePtsUs = -1;
    this.playStartNs = -1;

    if (this.playerThread != null) {
      final ExecutorService playerThread = requireNonNull(this.playerThread);
      ExecutorUtils.shutdownExecutorGracefully(playerThread);
      this.playerThread = null;
    }

    if (this.audioProcessor != null) {
      final ThreadPoolExecutor audioProcessor = requireNonNull(this.audioProcessor);
      audioProcessor.shutdownNow();
      this.audioProcessor = null;
    }

    if (this.videoProcessor != null) {
      final ThreadPoolExecutor videoProcessor = requireNonNull(this.videoProcessor);
      videoProcessor.shutdownNow();
      this.videoProcessor = null;
    }

    if (this.grabber != null) {
      final FrameGrabber grabber = requireNonNull(this.grabber);
      try {
        grabber.stop();
        grabber.close();
      } catch (final FrameGrabber.Exception e) {
        final String raw = e.getMessage();
        final Class<?> clazz = e.getClass();
        final String msg = raw != null ? raw : clazz.getName();
        this.exceptionHandler.accept(msg, e);
      }
      this.grabber = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DimensionAttachableCallback getDimensionAttachableCallback() {
    return this.dimensionCallback;
  }

  @Override
  public abstract FrameGrabber getFrameGrabber(final String resource);
}
