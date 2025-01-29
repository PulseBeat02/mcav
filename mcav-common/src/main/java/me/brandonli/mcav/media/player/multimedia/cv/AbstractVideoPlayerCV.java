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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
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
 * A full JavaCV-based video player that supports both audio and video playback.
 */
public abstract class AbstractVideoPlayerCV implements VideoPlayerCV {

  private static final int BUFFER_CAPACITY = 128;

  private final AtomicBoolean running;
  private final ExecutorService audioFrameRetrieverExecutor;
  private final ExecutorService videoFrameRetrieverExecutor;
  private final ExecutorService audioFrameProcessorExecutor;
  private final ExecutorService videoFrameProcessorExecutor;
  private final BlockingQueue<FramePacket.VideoFramePacket> videoFrameBuffer;
  private final BlockingQueue<FramePacket.AudioFramePacket> audioFrameBuffer;
  private final Lock lock;

  private volatile @Nullable AudioPipelineStep audioPipeline;
  private volatile @Nullable VideoPipelineStep videoPipeline;
  private volatile @Nullable FrameGrabber audio;
  private volatile @Nullable FrameGrabber video;
  private volatile @Nullable Source audioSource;
  private volatile @Nullable Source videoSource;
  private volatile @Nullable Long timestamp;

  public AbstractVideoPlayerCV() {
    this.running = new AtomicBoolean(false);
    this.audioFrameProcessorExecutor = Executors.newSingleThreadExecutor();
    this.videoFrameProcessorExecutor = Executors.newSingleThreadExecutor();
    this.audioFrameRetrieverExecutor = Executors.newSingleThreadExecutor();
    this.videoFrameRetrieverExecutor = Executors.newSingleThreadExecutor();
    this.videoFrameBuffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    this.audioFrameBuffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    this.timestamp = 0L;
    this.lock = new ReentrantLock();
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
      this.shutdownGrabbers();
      this.startGrabbers(audioPipeline, videoPipeline, video, audio);
      this.startRetrievers();
      return true;
    });
  }

  private void startRetrievers() {
    this.running.set(true);
    this.audioFrameRetrieverExecutor.submit(this::retrieveFramesMultiplexerAudio);
    this.audioFrameProcessorExecutor.submit(this::processAudioFrames);
    this.videoFrameProcessorExecutor.submit(this::retrieveFramesMultiplexerVideo);
    this.videoFrameRetrieverExecutor.submit(this::processVideoFrames);
  }

  private void startGrabbers(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) {
    final String videoResource = video.getResource();
    final String audioResource = audio.getResource();
    this.audioPipeline = audioPipeline;
    this.videoPipeline = videoPipeline;
    this.audioSource = audio;
    this.videoSource = video;
    this.audio = this.getFrameGrabber(audioResource);
    this.video = this.getFrameGrabber(videoResource);
    final FrameGrabber finalAudio = requireNonNull(this.audio);
    final FrameGrabber finalVideo = requireNonNull(this.video);
    finalAudio.setSampleMode(FrameGrabber.SampleMode.SHORT);
    finalAudio.setSampleFormat(AV_SAMPLE_FMT_S16);
    finalAudio.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
    finalVideo.setPixelFormat(AV_PIX_FMT_BGR24);
    try {
      finalAudio.start();
      finalVideo.start();
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void retrieveFramesMultiplexerAudio() {
    final FrameGrabber audio = requireNonNull(this.audio);
    final String codec = audio.getAudioCodecName();
    final int bitrate = audio.getAudioBitrate();
    final int sampleRate = audio.getSampleRate();
    final int channels = audio.getAudioChannels();
    final AudioMetadata audioMetadata = AudioMetadata.of(codec, bitrate, sampleRate, channels);
    try {
      Frame audioFrame;
      while ((audioFrame = audio.grabFrame()) != null && this.running.get()) {
        final EnumSet<Frame.Type> audioTypes = audioFrame.getTypes();
        if (!audioTypes.contains(Frame.Type.AUDIO)) {
          continue;
        }
        final Buffer[] arr = audioFrame.samples;
        if (arr == null) {
          continue;
        }
        final ByteBuffer outBuffer = ByteUtils.convertAudioSamples(arr[0]);
        final FramePacket.AudioFramePacket packet = FramePacket.audio(outBuffer, audioMetadata, audioFrame.timestamp);
        this.audioFrameBuffer.put(packet);
      }
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void retrieveFramesMultiplexerVideo() {
    final FrameGrabber video = requireNonNull(this.video);
    final int width = video.getImageWidth();
    final int height = video.getImageHeight();
    final int bitrate = video.getVideoBitrate();
    final float frameRate = (float) video.getFrameRate();
    final VideoMetadata videoMetadata = VideoMetadata.of(width, height, bitrate, frameRate);
    try {
      Frame videoFrame;
      while ((videoFrame = video.grabFrame()) != null && this.running.get()) {
        final EnumSet<Frame.Type> videoTypes = videoFrame.getTypes();
        if (!videoTypes.contains(Frame.Type.VIDEO)) {
          continue;
        }
        final Buffer[] arr = videoFrame.image;
        if (arr == null) {
          continue;
        }
        final ByteBuffer imageBuffer = (ByteBuffer) arr[0];
        imageBuffer.rewind();
        final byte[] bytes = new byte[imageBuffer.limit()];
        imageBuffer.get(bytes);
        final ByteBuffer copyBuffer = ByteBuffer.wrap(bytes);
        copyBuffer.position(0);
        final FramePacket.VideoFramePacket packet = FramePacket.video(copyBuffer, videoMetadata, width, height, videoFrame.timestamp);
        this.videoFrameBuffer.put(packet);
      }
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void retrieveFrames() {
    final FrameGrabber video = requireNonNull(this.video);
    final int width = video.getImageWidth();
    final int height = video.getImageHeight();
    final String codec = video.getVideoCodecName();
    final int bitrate = video.getVideoBitrate();
    final int sampleRate = video.getSampleRate();
    final int channels = video.getAudioChannels();
    final int videoBitrate = video.getVideoBitrate();
    final float frameRate = (float) video.getFrameRate();
    final AudioMetadata audioMetadata = AudioMetadata.of(codec, bitrate, sampleRate, channels);
    final VideoMetadata videoMetadata = VideoMetadata.of(width, height, videoBitrate, frameRate);
    try {
      Frame frame;
      while ((frame = video.grabFrame()) != null && this.running.get()) {
        final EnumSet<Frame.Type> types = frame.getTypes();
        if (types.contains(Frame.Type.VIDEO)) {
          final Buffer[] arr = frame.image;
          if (arr == null) {
            continue;
          }
          final ByteBuffer imageBuffer = (ByteBuffer) arr[0];
          imageBuffer.rewind();
          final byte[] bytes = new byte[imageBuffer.limit()];
          imageBuffer.get(bytes);
          final ByteBuffer copyBuffer = ByteBuffer.wrap(bytes);
          copyBuffer.position(0);
          final FramePacket.VideoFramePacket packet = FramePacket.video(copyBuffer, videoMetadata, width, height, frame.timestamp);
          this.videoFrameBuffer.put(packet);
        }
        if (types.contains(Frame.Type.AUDIO)) {
          final Buffer[] arr = frame.samples;
          if (arr == null) {
            continue;
          }
          final ByteBuffer outBuffer = ByteUtils.convertAudioSamples(arr[0]);
          final FramePacket.AudioFramePacket packet = FramePacket.audio(outBuffer, audioMetadata, frame.timestamp);
          this.audioFrameBuffer.put(packet);
        }
      }
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void processAudioFrames() {
    try {
      final int bufferingThreshold = BUFFER_CAPACITY / 2;
      if (this.running.get()) {
        while (this.audioFrameBuffer.size() < bufferingThreshold && this.running.get()) {
          Thread.sleep(50);
        }
      }
      long startTime = System.nanoTime();
      long firstFrameTimestamp = -1;
      while (this.running.get()) {
        final FramePacket frame = this.audioFrameBuffer.take();
        if (firstFrameTimestamp == -1) {
          firstFrameTimestamp = frame.getTimestamp();
          startTime = System.nanoTime();
        }
        final long currentTime = System.nanoTime();
        final long elapsedRealTime = (currentTime - startTime) / 1000;
        final long mediaTime = frame.getTimestamp() - firstFrameTimestamp;
        if (mediaTime > elapsedRealTime && this.running.get()) {
          final long waitTime = (mediaTime - elapsedRealTime) / 1000;
          if (waitTime > 0) {
            Thread.sleep(waitTime);
          }
        }
        final boolean shouldSkip = mediaTime < elapsedRealTime - 500000;
        if (shouldSkip && this.audioFrameBuffer.size() > 15) {
          continue;
        }
        final ByteBuffer buffer = frame.getData();
        AudioPipelineStep next = this.audioPipeline;
        final AudioMetadata audioMetadata = (AudioMetadata) frame.getMetadata();
        final int sampleRate = audioMetadata.getAudioSampleRate();
        final ByteBuffer samples = ByteUtils.resampleBufferCubic(buffer, sampleRate, 48000);
        while (next != null) {
          next.process(samples, audioMetadata);
          next = next.next();
        }
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void processVideoFrames() {
    try {
      final int bufferingThreshold = BUFFER_CAPACITY / 2;
      if (this.running.get()) {
        while (this.videoFrameBuffer.size() < bufferingThreshold && this.running.get()) {
          Thread.sleep(20);
        }
      }
      long startTime = System.nanoTime();
      long firstFrameTimestamp = -1;
      while (this.running.get()) {
        final FramePacket.VideoFramePacket frame = this.videoFrameBuffer.take();
        if (firstFrameTimestamp == -1) {
          firstFrameTimestamp = frame.getTimestamp();
          startTime = System.nanoTime();
        }
        final long currentTime = System.nanoTime();
        final long elapsedRealTime = (currentTime - startTime) / 1000;
        final long mediaTime = frame.getTimestamp() - firstFrameTimestamp;
        if (mediaTime > elapsedRealTime && this.running.get()) {
          final long waitTime = (mediaTime - elapsedRealTime) / 1000;
          if (waitTime > 0) {
            Thread.sleep(waitTime);
          }
        }
        final boolean shouldSkip = mediaTime < elapsedRealTime - 500000;
        if (shouldSkip && this.videoFrameBuffer.size() > 15) {
          continue;
        }
        final ByteBuffer buffer = frame.getData();
        final byte[] arr = buffer.array();
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final ImageBuffer staticImage = ImageBuffer.bytes(arr, width, height);
        VideoPipelineStep next = this.videoPipeline;
        while (next != null) {
          next.process(staticImage, (VideoMetadata) frame.getMetadata());
          next = next.next();
        }
        staticImage.release();
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // audio sample specialization
  // PCM S16LE
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined) {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.shutdownGrabbers();
      this.startGrabbers(audioPipeline, videoPipeline, combined);
      this.startRetrieversSingle();
      return true;
    });
  }

  private void startRetrieversSingle() {
    this.running.set(true);
    this.videoFrameRetrieverExecutor.submit(this::retrieveFrames);
    this.audioFrameProcessorExecutor.submit(this::processAudioFrames);
    this.videoFrameProcessorExecutor.submit(this::processVideoFrames);
  }

  private void startGrabbers(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined) {
    final String videoResource = combined.getResource();
    this.video = this.getFrameGrabber(videoResource);
    this.audio = null;
    this.audioPipeline = audioPipeline;
    this.videoPipeline = videoPipeline;
    this.audioSource = combined;
    this.videoSource = combined;
    final FrameGrabber finalGrabber = requireNonNull(this.video);
    finalGrabber.setPixelFormat(AV_PIX_FMT_BGR24);
    finalGrabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
    finalGrabber.setSampleFormat(AV_SAMPLE_FMT_S16);
    finalGrabber.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
    if (combined instanceof final FFmpegDirectSource directSource) {
      final String format = directSource.getFormat();
      finalGrabber.setFormat(format);
    }
    try {
      finalGrabber.start();
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.shutdownGrabbers();
      return true;
    });
  }

  private void shutdownGrabbers() {
    this.running.set(false);
    if (this.video != null) {
      final FrameGrabber videoGrabber = requireNonNull(this.video);
      this.timestamp = videoGrabber.getTimestamp();
    }
    try {
      this.shutdownGrabber(this.audio);
      this.shutdownGrabber(this.video);
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
    this.audio = null;
    this.video = null;
    this.audioFrameBuffer.clear();
    this.videoFrameBuffer.clear();
  }

  private void shutdownGrabber(final @Nullable FrameGrabber grabber) throws FrameGrabber.Exception {
    if (grabber != null) {
      grabber.stop();
      grabber.close();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.videoSource == null || this.timestamp == null) {
        return false;
      }
      this.shutdownGrabbers();
      this.applyTimeStamp();
      this.startResumeGrabbers();
      return true;
    });
  }

  private void startResumeGrabbers() {
    this.running.set(true);
    final boolean multiplexer = this.audioSource != this.videoSource;
    final FrameGrabber video = requireNonNull(this.video);
    try {
      if (multiplexer) {
        final FrameGrabber audio = requireNonNull(this.audio);
        audio.start();
        video.start();
        this.videoFrameRetrieverExecutor.submit(this::retrieveFramesMultiplexerVideo);
        this.audioFrameRetrieverExecutor.submit(this::retrieveFramesMultiplexerAudio);
      } else {
        video.start();
        this.videoFrameRetrieverExecutor.submit(this::retrieveFrames);
      }
      this.audioFrameProcessorExecutor.submit(this::processAudioFrames);
      this.videoFrameProcessorExecutor.submit(this::processVideoFrames);
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  private void applyTimeStamp() {
    final Source videoSource = requireNonNull(this.videoSource);
    final long timestamp = requireNonNull(this.timestamp);
    final String videoResource = videoSource.getResource();
    this.audioFrameBuffer.clear();
    this.videoFrameBuffer.clear();
    try {
      final FrameGrabber video = this.getFrameGrabber(videoResource);
      video.setTimestamp(timestamp);
      this.video = video;
      final boolean multiplexer = this.audioSource != this.videoSource;
      if (multiplexer) {
        final Source audioSource = requireNonNull(this.audioSource);
        final String audioResource = audioSource.getResource();
        final FrameGrabber audio = this.getFrameGrabber(audioResource);
        audio.setTimestamp(timestamp);
        this.audio = audio;
      }
    } catch (final FrameGrabber.Exception e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) {
    this.timestamp = time;
    return this.resume();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      ExecutorUtils.shutdownExecutorGracefully(this.audioFrameProcessorExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.audioFrameProcessorExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.videoFrameRetrieverExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.videoFrameProcessorExecutor);
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public abstract FrameGrabber getFrameGrabber(String resource);
}
