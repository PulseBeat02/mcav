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
package me.brandonli.mcav.media.player.image;

import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FrameSource;
import me.brandonli.mcav.utils.ExecutorUtils;

/**
 * The ImagePlayerImpl class implements the ImagePlayer interface and provides the functionality
 * for processing video frames using a pipeline of video processing steps. The class leverages
 * concurrency and asynchronous processing to handle video frames and execute pipeline steps in parallel.
 * <p>
 * This implementation uses two executor services: one for managing overall pipeline execution and another
 * for intensive frame processing tasks. It also ensures thread safety and proper resource management.
 */
public class ImagePlayerImpl implements ImagePlayer {

  private final AtomicBoolean running;
  private final ExecutorService executor;
  private final ExecutorService processor;
  private final Object lock;

  private volatile CompletableFuture<Void> future;
  private volatile FrameSource source;
  private volatile VideoPipelineStep videoPipelineStep;

  ImagePlayerImpl() {
    this.lock = new Object();
    this.running = new AtomicBoolean(false);
    this.executor = Executors.newCachedThreadPool();
    this.processor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final FrameSource source) throws Exception {
    synchronized (this.lock) {
      if (this.running.get()) {
        return false;
      }
      this.videoPipelineStep = videoPipeline;
      this.source = source;
      this.running.set(true);
      this.future = CompletableFuture.runAsync(this::runExecutorTask, this.executor);
      return true;
    }
  }

  private void runExecutorTask() {
    final VideoMetadata metadata = this.source.getVideoMetadata();
    final int width = metadata.getVideoWidth();
    final int height = metadata.getVideoHeight();
    while (this.running.get()) {
      final IntBuffer frame = this.source.getFrameSupplier().get();
      final int[] data = frame.array();
      final StaticImage image = StaticImage.buffer(data, width, height);
      VideoPipelineStep next = this.videoPipelineStep;
      while (next != null) {
        next.process(image, metadata);
        next = next.next();
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() throws Exception {
    synchronized (this.lock) {
      this.running.set(false);
      if (this.future != null) {
        this.future.cancel(true);
      }
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
      ExecutorUtils.shutdownExecutorGracefully(this.processor);
      return true;
    }
  }
}
