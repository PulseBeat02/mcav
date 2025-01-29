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

import static java.util.Objects.requireNonNull;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import javax.sound.sampled.*;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FFmpegDirectSource;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.natives.ByteUtils;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract implementation of a video player that uses JavaCV for multimedia processing.
 */
public abstract class AbstractVideoPlayerCV implements VideoPlayerCV {

  private static final long MAX_DESYNC_NS = 10_000_000L;

  private final DimensionAttachableCallback dimensionCallback;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;

  @Nullable private volatile ExecutorService playerThread;

  @Nullable private volatile ThreadPoolExecutor audioProcessor;

  @Nullable private volatile ThreadPoolExecutor videoProcessor;

  @Nullable private volatile FrameGrabber grabber;

  @Nullable private volatile Source currentSource;

  @Nullable private volatile Long seekPosition;

  @Nullable private volatile SourceDataLine audioLine;

  private final AtomicLong audioFrames;
  private final AtomicLong videoFrames;
  private final AtomicBoolean running;
  private final Lock lock;

  /**
   * Constructs a new AbstractVideoPlayerCV instance.
   */
  public AbstractVideoPlayerCV() {
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.videoCallback = VideoAttachableCallback.create();
    this.audioCallback = AudioAttachableCallback.create();
    this.running = new AtomicBoolean(false);
    this.audioFrames = new AtomicLong(0);
    this.videoFrames = new AtomicLong(0);
    this.lock = new ReentrantLock();
  }

  @Override
  public boolean start(final Source video, final Source audio) {
    return LockUtils.executeWithLock(this.lock, () -> {
      try {
        this.stop();
        this.grabber = this.createGrabber(video);
        this.currentSource = video;
        this.startPlaybackWithSeparateAudio(audio);
        return true;
      } catch (final Exception e) {
        this.stop();
        throw new PlayerException(e.getMessage(), e);
      }
    });
  }

  private void startPlaybackWithSeparateAudio(final Source audioSource) throws LineUnavailableException {
    this.running.set(true);
    this.audioFrames.set(0);
    this.videoFrames.set(0);

    final AudioFormat fmt = this.getAudioFormat();
    final DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
    final int bufferBytes = (int) (fmt.getFrameSize() * fmt.getFrameRate() * 0.2);
    final SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);
    audioLine.open(fmt, bufferBytes);
    audioLine.start();
    this.audioLine = audioLine;

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
    final ExecutorService audioExec = requireNonNull(this.audioProcessor);
    final ExecutorService videoExec = requireNonNull(this.videoProcessor);
    FrameGrabber audioGrabber = null;
    boolean separateAudioSource = !audioSource.getResource().equals(videoGrabber.getFormat());
    if (separateAudioSource) {
      try {
        audioGrabber = this.createAudioGrabber(audioSource);
      } catch (final Exception e) {
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

    final OriginalAudioMetadata audioMeta = OriginalAudioMetadata.of(
      videoGrabber.getAudioCodecName(),
      videoGrabber.getAudioBitrate(),
      videoGrabber.getSampleRate(),
      videoGrabber.getAudioChannels(),
      videoGrabber.getSampleFormat()
    );

    try {
      if (separateAudioSource) {
        requireNonNull(audioGrabber);
        this.playbackSeparateSources(videoGrabber, audioGrabber, videoMeta, audioMeta, audioExec, videoExec);
      } else {
        this.playbackCombinedSource(videoGrabber, videoMeta, audioMeta, audioExec, videoExec);
      }
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    } finally {
      if (audioGrabber != null) {
        try {
          audioGrabber.stop();
          audioGrabber.close();
        } catch (final FrameGrabber.Exception ignored) {}
      }
    }
  }

  private void playbackSeparateSources(
    final FrameGrabber videoGrabber,
    final FrameGrabber audioGrabber,
    final OriginalVideoMetadata videoMeta,
    final OriginalAudioMetadata audioMeta,
    final ExecutorService audioExec,
    final ExecutorService videoExec
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
        throw new PlayerException(e.getMessage(), e);
      }
    });

    Frame videoFrame;
    while ((videoFrame = videoGrabber.grab()) != null && this.running.get()) {
      if (videoFrame.image != null) {
        final Frame copy = videoFrame.clone();
        videoExec.submit(() -> this.processVideoFrame(copy, videoMeta));
      }
    }

    audioSourceThread.shutdownNow();
    try {
      audioSourceThread.awaitTermination(1, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void playbackCombinedSource(
    final FrameGrabber grabber,
    final OriginalVideoMetadata videoMeta,
    final OriginalAudioMetadata audioMeta,
    final ExecutorService audioExec,
    final ExecutorService videoExec
  ) throws FrameGrabber.Exception {
    Frame frame;
    while ((frame = grabber.grab()) != null && this.running.get()) {
      if (frame.samples != null) {
        final Frame copy = frame.clone();
        audioExec.submit(() -> this.processAudioFrame(copy, audioMeta));
      }
      if (frame.image != null) {
        final Frame copy = frame.clone();
        videoExec.submit(() -> this.processVideoFrame(copy, videoMeta));
      }
    }
  }

  private FrameGrabber createAudioGrabber(final Source audioSource) throws FrameGrabber.Exception {
    final String resource = audioSource.getResource();
    final FrameGrabber grabber = this.getFrameGrabber(resource);
    grabber.setOption("tune", "zerolatency");
    grabber.setOption("preset", "ultrafast");
    grabber.setOption("threads", "auto");
    grabber.setOption("fflags", "nobuffer+fastseek+flush_packets");
    grabber.setOption("flags", "low_delay");
    grabber.setOption("audio_buffer_size", "16384");
    grabber.setOption("thread_queue_size", "16384");

    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
    grabber.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
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

  @Override
  public boolean start(final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      try {
        this.stop();
        this.grabber = this.createGrabber(combined);
        this.currentSource = combined;
        this.startPlayback();
        return true;
      } catch (final Exception e) {
        this.stop();
        throw new PlayerException(e.getMessage(), e);
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
    grabber.setOption("fflags", "nobuffer+fastseek+flush_packets");
    grabber.setOption("flags", "low_delay");
    grabber.setOption("probesize", "32");
    grabber.setOption("analyzeduration", "0");
    grabber.setOption("audio_buffer_size", "16384");
    grabber.setOption("reorder_queue_size", "0");
    grabber.setOption("thread_queue_size", "16384");
    grabber.setOption("avoid_negative_ts", "disabled");
    grabber.setOption("rtbufsize", "2048k");
    grabber.setOption("buffer_size", "2048k");
    grabber.setOption("hwaccel", "auto");
    grabber.setPixelFormat(AV_PIX_FMT_BGR24);
    grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
    grabber.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
    grabber.setSampleRate(48000);
    grabber.setAudioChannels(2);

    if (this.dimensionCallback.isAttached()) {
      final DimensionAttachableCallback.Dimension dim = this.dimensionCallback.retrieve();
      grabber.setImageWidth(dim.width());
      grabber.setImageHeight(dim.height());
    }

    if (source instanceof final FFmpegDirectSource direct) {
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

  private void startPlayback() throws LineUnavailableException {
    this.running.set(true);

    this.audioFrames.set(0);
    this.videoFrames.set(0);

    final AudioFormat fmt = this.getAudioFormat();
    final DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
    final int bufferBytes = (int) (fmt.getFrameSize() * fmt.getFrameRate() * 0.2);
    final SourceDataLine audioLine = (SourceDataLine) AudioSystem.getLine(info);
    audioLine.open(fmt, bufferBytes);
    audioLine.start();

    this.audioLine = audioLine;

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

  private AudioFormat getAudioFormat() {
    final FrameGrabber grabber = requireNonNull(this.grabber);
    final int sampleRate = grabber.getSampleRate();
    final int channels = grabber.getAudioChannels();
    return new AudioFormat(sampleRate, 16, channels, true, false);
  }

  private void playback() {
    final FrameGrabber grabber = requireNonNull(this.grabber);
    final ExecutorService audioExec = requireNonNull(this.audioProcessor);
    final ExecutorService videoExec = requireNonNull(this.videoProcessor);
    final float detectedFps = (float) grabber.getFrameRate();
    final float targetFps = detectedFps > 0 ? detectedFps : 30f;
    final OriginalVideoMetadata videoMeta = OriginalVideoMetadata.of(
      grabber.getImageWidth(),
      grabber.getImageHeight(),
      grabber.getVideoBitrate(),
      targetFps
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
          final Frame copy = frame.clone();
          audioExec.submit(() -> this.processAudioFrame(copy, audioMeta));
        }
        if (frame.image != null) {
          final Frame copy = frame.clone();
          videoExec.submit(() -> this.processVideoFrame(copy, videoMeta));
        }
      }
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void processAudioFrame(final Frame frame, final OriginalAudioMetadata meta) {
    try (frame) {
      final ByteBuffer data = ByteUtils.convertAudioSamples(frame.samples[0]);
      final ByteBuffer buf = ByteUtils.convertToLittleEndian(data);
      final byte[] pcm = new byte[buf.remaining()];
      buf.get(pcm);

      final SourceDataLine audioLine = requireNonNull(this.audioLine);
      audioLine.write(pcm, 0, pcm.length);
      AudioPipelineStep step = this.audioCallback.retrieve();

      final ByteBuffer wrap = ByteBuffer.wrap(pcm);
      while (step != null) {
        step.process(wrap, meta);
        step = step.next();
      }
    } catch (final Exception ignored) {}
  }

  private void processVideoFrame(final Frame frame, final OriginalVideoMetadata meta) {
    try (frame) {
      final long timestamp = frame.timestamp;
      final int width = frame.imageWidth;
      final int height = frame.imageHeight;
      final SourceDataLine audioLine = requireNonNull(this.audioLine);
      final long audioUs = audioLine.getMicrosecondPosition();
      final long delayUs = timestamp - audioUs;

      if (delayUs > 0) {
        final long nanos = TimeUnit.MICROSECONDS.toNanos(delayUs);
        if (nanos > MAX_DESYNC_NS) {
          LockSupport.parkNanos(nanos - MAX_DESYNC_NS);
        }
        final long deadline = System.nanoTime() + Math.min(nanos, MAX_DESYNC_NS);
        while (System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
      }
      final ByteBuffer data = (ByteBuffer) frame.image[0];
      final ImageBuffer img = ImageBuffer.bytes(data, width, height);
      VideoPipelineStep step = this.videoCallback.retrieve();
      while (step != null) {
        step.process(img, meta);
        step = step.next();
      }
      img.release();
    } catch (final Exception ignored) {}
  }

  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.stop();
      return true;
    });
  }

  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> this.currentSource != null && this.start(this.currentSource));
  }

  @Override
  public boolean seek(final long time) {
    this.seekPosition = time;
    return this.resume();
  }

  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.stop();
      return true;
    });
  }

  private void stop() {
    this.running.set(false);

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
        throw new PlayerException(e.getMessage(), e);
      }
      this.grabber = null;
    }

    if (this.audioLine != null) {
      final SourceDataLine audioLine = requireNonNull(this.audioLine);
      audioLine.drain();
      audioLine.stop();
      audioLine.close();
      this.audioLine = null;
    }
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  @Override
  public DimensionAttachableCallback getDimensionAttachableCallback() {
    return this.dimensionCallback;
  }

  @Override
  public abstract FrameGrabber getFrameGrabber(final String resource);
}
