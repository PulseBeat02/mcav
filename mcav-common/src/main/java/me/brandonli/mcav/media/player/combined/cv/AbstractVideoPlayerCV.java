/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.player.combined.cv;

import static java.util.Objects.requireNonNull;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.EnumSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * AbstractVideoPlayerCV is an abstract implementation of the VideoPlayerCV interface
 * that defines a general-purpose video player with support for pipeline processing of
 * audio and video frames. This class provides mechanisms for multimedia playback,
 * frame multiplexing, pipeline-based processing, and lifecycle control for media streams.
 * <p>
 * Features supported by this class include:
 * - Initialization and management of video and audio frame grabbers.
 * - Concurrent processing of media frames using defined audio and video pipelines.
 * - Support for both audio/video multiplexed and video-only sources.
 * - Media playback lifecycle management, including start, pause, resume, seek, and release operations.
 * - Thread-safe operation management to ensure consistent behavior across multiple states.
 * <p>
 * Subclasses are expected to implement the {@link #getFrameGrabber(String)} method
 * to provide a concrete frame grabber implementation for fetching multimedia frames
 * (e.g., via FFmpeg or other video processing libraries).
 */
abstract class AbstractVideoPlayerCV implements VideoPlayerCV {

  private final AtomicBoolean running;
  private final ExecutorService frameRetrieverExecutor;
  private final ExecutorService frameProcessorExecutor;
  private final BlockingQueue<MediaFrame> videoFrameBuffer;
  private final BlockingQueue<MediaFrame> audioFrameBuffer;
  private final Object lock;

  private static final int BUFFER_CAPACITY = 128;

  private volatile @Nullable FrameGrabber audio;
  private volatile @Nullable FrameGrabber video;
  private volatile @Nullable CompletableFuture<Void> retrievalFuture;
  private volatile @Nullable CompletableFuture<Void> audioProcessingFuture;
  private volatile @Nullable CompletableFuture<Void> videoProcessingFuture;

  private volatile long timestamp;
  private volatile Source audioSource;
  private volatile Source videoSource;
  private volatile AudioPipelineStep audioPipeline;
  private volatile VideoPipelineStep videoPipeline;

  private static class MediaFrame {

    private final ByteBuffer data;
    private final Object metadata;
    private final int width;
    private final int height;
    private final long timestamp;

    private MediaFrame(final ByteBuffer data, final Object metadata, final int width, final int height, final long timestamp) {
      this.data = data;
      this.metadata = metadata;
      this.width = width;
      this.height = height;
      this.timestamp = timestamp;
    }

    static MediaFrame audio(final ByteBuffer data, final AudioMetadata metadata, final long timestamp) {
      return new MediaFrame(data, metadata, 0, 0, timestamp);
    }

    static MediaFrame video(final ByteBuffer data, final VideoMetadata metadata, final int width, final int height, final long timestamp) {
      return new MediaFrame(data, metadata, width, height, timestamp);
    }
  }

  public AbstractVideoPlayerCV() {
    this.lock = new Object();
    this.running = new AtomicBoolean(false);
    this.frameRetrieverExecutor = Executors.newSingleThreadExecutor();
    this.frameProcessorExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    this.videoFrameBuffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    this.audioFrameBuffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
  }

  @Override
  public boolean start(
    final AudioPipelineStep audioPipeline,
    final VideoPipelineStep videoPipeline,
    final Source video,
    final Source audio
  ) throws FrameGrabber.Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();

      this.audioPipeline = audioPipeline;
      this.videoPipeline = videoPipeline;
      this.audioSource = audio;
      this.videoSource = video;

      final String videoResource = video.getResource();
      final String audioResource = audio.getResource();
      this.audio = this.getFrameGrabber(audioResource);
      this.video = this.getFrameGrabber(videoResource);

      final FrameGrabber finalAudio = requireNonNull(this.audio);
      finalAudio.setSampleMode(FrameGrabber.SampleMode.FLOAT);

      final FrameGrabber finalVideo = requireNonNull(this.video);
      finalVideo.setPixelFormat(AV_PIX_FMT_BGR24);

      finalAudio.start();
      finalVideo.start();

      this.running.set(true);
      this.retrievalFuture = CompletableFuture.runAsync(this::retrieveFramesMultiplexer, this.frameRetrieverExecutor);
      this.audioProcessingFuture = CompletableFuture.runAsync(this::processAudioFrames, this.frameProcessorExecutor);
      this.videoProcessingFuture = CompletableFuture.runAsync(this::processVideoFrames, this.frameProcessorExecutor);

      return true;
    }
  }

  private void retrieveFramesMultiplexer() {
    final FrameGrabber audio = requireNonNull(this.audio);
    final AudioMetadata audioMetadata = AudioMetadata.of(audio.getAudioBitrate(), audio.getSampleRate(), audio.getAudioChannels());
    final FrameGrabber video = requireNonNull(this.video);
    final int width = video.getImageWidth();
    final int height = video.getImageHeight();
    final VideoMetadata videoMetadata = VideoMetadata.of(width, height, video.getVideoBitrate(), (float) video.getFrameRate());
    try {
      Frame audioFrame;
      Frame videoFrame;
      while ((audioFrame = audio.grabFrame()) != null && (videoFrame = video.grabFrame()) != null && this.running.get()) {
        final EnumSet<Frame.Type> audioTypes = audioFrame.getTypes();
        if (audioTypes.contains(Frame.Type.AUDIO)) {
          final Buffer[] arr = audioFrame.samples;
          if (arr != null) {
            final ByteBuffer outBuffer = this.convertSamples(arr[0]);
            this.audioFrameBuffer.put(MediaFrame.audio(outBuffer, audioMetadata, audioFrame.timestamp));
          }
        }
        final EnumSet<Frame.Type> videoTypes = videoFrame.getTypes();
        if (videoTypes.contains(Frame.Type.VIDEO)) {
          final Buffer[] arr = videoFrame.image;
          if (arr != null) {
            final ByteBuffer imageBuffer = (ByteBuffer) arr[0];
            imageBuffer.rewind();
            final byte[] bytes = new byte[imageBuffer.limit()];
            imageBuffer.get(bytes);
            final ByteBuffer copyBuffer = ByteBuffer.wrap(bytes);
            copyBuffer.position(0);
            this.videoFrameBuffer.put(MediaFrame.video(copyBuffer, videoMetadata, width, height, videoFrame.timestamp));
          }
        }
      }
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PlayerException(e.getMessage());
    }
  }

  private void retrieveFrames() {
    final FrameGrabber video = requireNonNull(this.video);
    final int width = video.getImageWidth();
    final int height = video.getImageHeight();
    final AudioMetadata audioMetadata = AudioMetadata.of(video.getAudioBitrate(), video.getSampleRate(), video.getAudioChannels());
    final VideoMetadata videoMetadata = VideoMetadata.of(width, height, video.getVideoBitrate(), (float) video.getFrameRate());

    try {
      Frame frame;
      while ((frame = video.grabFrame()) != null && this.running.get()) {
        final EnumSet<Frame.Type> types = frame.getTypes();
        if (types.contains(Frame.Type.VIDEO)) {
          final Buffer[] arr = frame.image;
          if (arr != null) {
            final ByteBuffer imageBuffer = (ByteBuffer) arr[0];
            imageBuffer.rewind();
            final byte[] bytes = new byte[imageBuffer.limit()];
            imageBuffer.get(bytes);
            final ByteBuffer copyBuffer = ByteBuffer.wrap(bytes);
            copyBuffer.position(0);
            this.videoFrameBuffer.put(MediaFrame.video(copyBuffer, videoMetadata, width, height, frame.timestamp));
          }
        }
        if (types.contains(Frame.Type.AUDIO)) {
          final Buffer[] arr = frame.samples;
          if (arr != null) {
            final ByteBuffer outBuffer = this.convertSamples(arr[0]);
            this.audioFrameBuffer.put(MediaFrame.audio(outBuffer, audioMetadata, frame.timestamp));
          }
        }
      }
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      Thread.currentThread().interrupt();
      this.running.set(false);
    }
  }

  private void processAudioFrames() {
    try {
      while (this.running.get() || !this.audioFrameBuffer.isEmpty()) {
        final MediaFrame frame = this.audioFrameBuffer.take();
        AudioPipelineStep next = this.audioPipeline;
        while (next != null) {
          next.process(frame.data, (AudioMetadata) frame.metadata);
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
      while (this.running.get() || !this.videoFrameBuffer.isEmpty()) {
        final MediaFrame frame = this.videoFrameBuffer.take();
        if (firstFrameTimestamp == -1) {
          firstFrameTimestamp = frame.timestamp;
          startTime = System.nanoTime();
        }
        final long currentTime = System.nanoTime();
        final long elapsedRealTime = (currentTime - startTime) / 1000;
        final long mediaTime = frame.timestamp - firstFrameTimestamp;
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
        final StaticImage staticImage = StaticImage.bytes(frame.data.array(), frame.width, frame.height);
        VideoPipelineStep next = this.videoPipeline;
        while (next != null) {
          next.process(staticImage, (VideoMetadata) frame.metadata);
          next = next.next();
        }
        staticImage.release();
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined)
    throws FrameGrabber.Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();

      this.audioPipeline = audioPipeline;
      this.videoPipeline = videoPipeline;
      this.audioSource = combined;
      this.videoSource = combined;

      final String videoResource = combined.getResource();
      this.video = this.getFrameGrabber(videoResource);
      this.audio = null;

      final FrameGrabber finalGrabber = requireNonNull(this.video);
      finalGrabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
      finalGrabber.setPixelFormat(AV_PIX_FMT_BGR24);
      finalGrabber.setSampleFormat(AV_SAMPLE_FMT_S16);
      finalGrabber.start();

      this.running.set(true);
      this.retrievalFuture = CompletableFuture.runAsync(this::retrieveFrames, this.frameRetrieverExecutor);
      this.audioProcessingFuture = CompletableFuture.runAsync(this::processAudioFrames, this.frameProcessorExecutor);
      this.videoProcessingFuture = CompletableFuture.runAsync(this::processVideoFrames, this.frameProcessorExecutor);

      return true;
    }
  }

  private ByteBuffer convertSamples(final Buffer buffer) {
    if (buffer instanceof FloatBuffer) {
      final FloatBuffer floatBuffer = (FloatBuffer) buffer;
      final ByteBuffer byteBuffer = ByteBuffer.allocate(floatBuffer.capacity() * 4);
      for (int i = 0; i < floatBuffer.capacity(); i++) {
        byteBuffer.putFloat(floatBuffer.get(i));
      }
      byteBuffer.flip();
      return byteBuffer;
    } else if (buffer instanceof ShortBuffer) {
      final ShortBuffer shortBuffer = (ShortBuffer) buffer;
      final ByteBuffer byteBuffer = ByteBuffer.allocate(shortBuffer.capacity() * 2);
      for (int i = 0; i < shortBuffer.capacity(); i++) {
        byteBuffer.putShort(shortBuffer.get(i));
      }
      byteBuffer.flip();
      return byteBuffer;
    } else if (buffer instanceof ByteBuffer) {
      return (ByteBuffer) buffer;
    } else {
      throw new PlayerException("Unsupported buffer type!");
    }
  }

  @Override
  public boolean pause() throws FrameGrabber.Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();
      return true;
    }
  }

  private void shutdownGrabbers() throws FrameGrabber.Exception {
    this.running.set(false);
    if (this.retrievalFuture != null) {
      this.retrievalFuture.cancel(true);
      this.retrievalFuture = null;
    }
    if (this.audioProcessingFuture != null) {
      this.audioProcessingFuture.cancel(true);
      this.audioProcessingFuture = null;
    }
    if (this.videoProcessingFuture != null) {
      this.videoProcessingFuture.cancel(true);
      this.videoProcessingFuture = null;
    }
    if (this.video != null) {
      this.timestamp = requireNonNull(this.video).getTimestamp();
    }
    this.shutdownGrabber(this.audio);
    this.shutdownGrabber(this.video);
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

  @Override
  public boolean resume() throws Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();

      final boolean multiplexer = this.audioSource != this.videoSource;
      this.video = this.getFrameGrabber(this.videoSource.getResource());
      requireNonNull(this.video).setTimestamp(this.timestamp);

      if (multiplexer) {
        this.audio = this.getFrameGrabber(this.audioSource.getResource());
        requireNonNull(this.audio).setTimestamp(this.timestamp);
      }

      this.running.set(true);

      if (multiplexer) {
        requireNonNull(this.audio).start();
        requireNonNull(this.video).start();
        this.retrievalFuture = CompletableFuture.runAsync(this::retrieveFramesMultiplexer, this.frameRetrieverExecutor);
      } else {
        requireNonNull(this.video).start();
        this.retrievalFuture = CompletableFuture.runAsync(this::retrieveFrames, this.frameRetrieverExecutor);
      }

      this.audioProcessingFuture = CompletableFuture.runAsync(this::processAudioFrames, this.frameProcessorExecutor);
      this.videoProcessingFuture = CompletableFuture.runAsync(this::processVideoFrames, this.frameProcessorExecutor);

      return true;
    }
  }

  @Override
  public boolean seek(final long time) throws Exception {
    synchronized (this.lock) {
      this.pause();
      this.timestamp = time;
      return this.resume();
    }
  }

  @Override
  public boolean release() throws Exception {
    synchronized (this.lock) {
      this.pause();
      ExecutorUtils.shutdownExecutorGracefully(this.frameRetrieverExecutor);
      ExecutorUtils.shutdownExecutorGracefully(this.frameProcessorExecutor);
      return true;
    }
  }

  @Override
  public abstract FrameGrabber getFrameGrabber(String resource);
}
