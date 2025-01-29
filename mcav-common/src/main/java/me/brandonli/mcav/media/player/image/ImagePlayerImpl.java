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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FrameSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.LockUtils;

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
  private final Lock lock;

  private volatile FrameSource source;
  private volatile VideoPipelineStep videoPipelineStep;

  ImagePlayerImpl() {
    this.lock = new ReentrantLock();
    this.running = new AtomicBoolean(false);
    this.executor = Executors.newSingleThreadExecutor();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final VideoPipelineStep videoPipeline, final FrameSource source) {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get()) {
        return false;
      }

      this.videoPipelineStep = videoPipeline;
      this.source = source;
      this.running.set(true);
      this.executor.submit(this::runExecutorTask);

      return true;
    });
  }

  private void runExecutorTask() {
    final int width = this.source.getFrameWidth();
    final int height = this.source.getFrameHeight();
    final VideoMetadata metadata = VideoMetadata.of(width, height);
    while (this.running.get()) {
      final IntBuffer frame = this.source.getFrameSupplier().get();
      if (frame == null || frame.remaining() == 0) {
        this.waitForFrame();
        continue;
      }
      final int[] data = frame.array();
      final ImageBuffer image = ImageBuffer.buffer(data, width, height);
      VideoPipelineStep next = this.videoPipelineStep;
      while (next != null) {
        next.process(image, metadata);
        next = next.next();
      }
      image.release();
    }
  }

  private void waitForFrame() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new PlayerException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      this.running.set(false);
      ExecutorUtils.shutdownExecutorGracefully(this.executor);
      return true;
    });
  }
}
