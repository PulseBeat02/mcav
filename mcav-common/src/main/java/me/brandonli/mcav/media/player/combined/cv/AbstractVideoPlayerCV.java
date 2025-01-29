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

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
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
  private final ExecutorService executor;
  private final ExecutorService processor;
  private final List<CompletableFuture<?>> pendingTasks;
  private final Object lock;

  private volatile @Nullable FrameGrabber audio;
  private volatile @Nullable FrameGrabber video;
  private volatile @Nullable CompletableFuture<Void> future;

  private volatile long timestamp;
  private volatile Source audioSource;
  private volatile Source videoSource;
  private volatile AudioPipelineStep audioPipeline;
  private volatile VideoPipelineStep videoPipeline;

  /**
   * Initializes an instance of AbstractVideoPlayerCV with the necessary data structures
   * and thread management utilities for video and audio playback processing.
   * <p>
   * This constructor sets up the following components:
   * - A lock object for synchronizing access to shared resources.
   * - An {@link AtomicBoolean} flag to track the running state of the video player.
   * - A cached thread pool for managing tasks that require dynamic thread allocation.
   * - A fixed thread pool, configured to leverage the available processors, for handling
   * intensive processing tasks.
   * - A thread-safe list to maintain a queue of pending tasks for execution.
   */
  public AbstractVideoPlayerCV() {
    this.lock = new Object();
    this.running = new AtomicBoolean(false);
    this.executor = Executors.newCachedThreadPool();
    this.processor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    this.pendingTasks = new CopyOnWriteArrayList<>();
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
      requireNonNull(this.audio).start();
      requireNonNull(this.video).start();

      this.running.set(true);
      this.future = CompletableFuture.runAsync(this::provideFramesMultiplexer, this.executor);

      return true;
    }
  }

  private void provideFramesMultiplexer() {
    final FrameGrabber audio = requireNonNull(this.audio);
    final AudioMetadata audioMetadata = AudioMetadata.of(audio.getAudioBitrate(), audio.getSampleRate(), audio.getAudioChannels());
    final FrameGrabber video = requireNonNull(this.video);
    final VideoMetadata videoMetadata = VideoMetadata.of(
      video.getImageWidth(),
      video.getImageHeight(),
      video.getVideoBitrate(),
      (float) video.getFrameRate()
    );
    try {
      final Java2DFrameConverter converter = new Java2DFrameConverter();
      Frame audioFrame;
      Frame videoFrame;
      while ((audioFrame = audio.grabAtFrameRate()) != null && (videoFrame = video.grabAtFrameRate()) != null && this.running.get()) {
        final Frame finalAudioFrame = audioFrame;
        final Frame finalVideoFrame = videoFrame;
        final CompletableFuture<?> task = CompletableFuture.runAsync(
          () -> {
            final ShortBuffer samples = (ShortBuffer) finalAudioFrame.samples[0];
            final ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
            for (int i = 0; i < samples.capacity(); i++) {
              outBuffer.putShort(samples.get(i));
            }
            AudioPipelineStep next = this.audioPipeline;
            while (next != null) {
              next.process(outBuffer, audioMetadata);
              next = next.next();
            }
            final BufferedImage image = converter.convert(finalVideoFrame);
            final StaticImage staticImage = StaticImage.image(image);
            VideoPipelineStep next1 = this.videoPipeline;
            while (next1 != null) {
              next1.process(staticImage, videoMetadata);
              next1 = next1.next();
            }
          },
          this.processor
        );
        this.pendingTasks.add(task);
        task.whenComplete((result, ex) -> this.pendingTasks.remove(task));
      }
      converter.close();
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new AssertionError(e);
    }
  }

  private void provideFrames() {
    final FrameGrabber video = requireNonNull(this.video);
    final AudioMetadata audioMetadata = AudioMetadata.of(video.getAudioBitrate(), video.getSampleRate(), video.getAudioChannels());
    final VideoMetadata videoMetadata = VideoMetadata.of(
      video.getImageWidth(),
      video.getImageHeight(),
      video.getVideoBitrate(),
      (float) video.getFrameRate()
    );
    try {
      final Java2DFrameConverter converter = new Java2DFrameConverter();
      Frame frame;
      while ((frame = video.grabAtFrameRate()) != null && this.running.get()) {
        final Frame finalFrame = frame;
        final CompletableFuture<?> task = CompletableFuture.runAsync(
          () -> {
            try {
              final ShortBuffer samples = (ShortBuffer) finalFrame.samples[0];
              final ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
              for (int i = 0; i < samples.capacity(); i++) {
                outBuffer.putShort(samples.get(i));
              }
              AudioPipelineStep next = this.audioPipeline;
              while (next != null) {
                next.process(outBuffer, audioMetadata);
                next = next.next();
              }
              final BufferedImage image = converter.convert(finalFrame);
              final StaticImage staticImage = StaticImage.image(image);
              VideoPipelineStep next1 = this.videoPipeline;
              while (next1 != null) {
                next1.process(staticImage, videoMetadata);
                next1 = next1.next();
              }
            } catch (final Exception e) {
              throw new AssertionError(e);
            }
          },
          this.processor
        );
        this.pendingTasks.add(task);
        task.whenComplete((result, ex) -> this.pendingTasks.remove(task));
      }
      converter.close();
    } catch (final FrameGrabber.Exception | InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new AssertionError(e);
    }
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
  public boolean start(final AudioPipelineStep audioPipeline, final VideoPipelineStep videoPipeline, final Source combined)
    throws FrameGrabber.Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();

      final String videoResource = combined.getResource();
      this.video = this.getFrameGrabber(videoResource);
      this.audio = null;
      requireNonNull(this.video).start();

      this.running.set(true);
      this.future = CompletableFuture.runAsync(this::provideFrames, this.executor);

      return true;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() throws FrameGrabber.Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();
      return true;
    }
  }

  private void shutdownGrabbers() throws FrameGrabber.Exception {
    this.running.set(false);
    if (this.future != null) {
      requireNonNull(this.future).cancel(true);
      this.future = null;
    }
    if (this.video != null) {
      this.timestamp = requireNonNull(this.video).getTimestamp();
      this.video = null;
    }
    this.shutdownGrabber(this.audio);
    this.shutdownGrabber(this.video);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() throws Exception {
    synchronized (this.lock) {
      this.shutdownGrabbers();

      final boolean multiplexer = this.audio != null;
      this.video = this.getFrameGrabber(this.videoSource.getResource());
      requireNonNull(this.video).setTimestamp(this.timestamp);

      if (multiplexer) {
        this.audio = this.getFrameGrabber(this.audioSource.getResource());
        requireNonNull(this.audio).setTimestamp(this.timestamp);
      }

      this.running.set(true);
      this.future = multiplexer
        ? CompletableFuture.runAsync(this::provideFramesMultiplexer, this.executor)
        : CompletableFuture.runAsync(this::provideFrames, this.executor);

      return true;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean seek(final long time) throws Exception {
    synchronized (this.lock) {
      this.pause();
      this.timestamp = time;
      this.resume();
      return true;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() throws Exception {
    synchronized (this.lock) {
      this.pause();
      try {
        CompletableFuture.allOf(this.pendingTasks.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
      } catch (final TimeoutException e) {
        throw new AssertionError(e);
      }
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
      ExecutorUtils.shutdownExecutorGracefully(this.processor);
      return true;
    }
  }
}
