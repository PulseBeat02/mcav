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
package me.brandonli.mcav.media.player.image;

import static java.util.Objects.requireNonNull;

import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.FrameSource;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.LockUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A default implementation of the {@link ImagePlayer} interface.
 */
public class ImagePlayerImpl implements ImagePlayer {

  private final VideoAttachableCallback callback;
  private final AtomicBoolean running;
  private final ExecutorService executor;
  private final Lock lock;

  @Nullable private volatile FrameSource source;

  private BiConsumer<String, Throwable> exceptionHandler;

  ImagePlayerImpl() {
    this.exceptionHandler = ExceptionHandler.createDefault().getExceptionHandler();
    this.lock = new ReentrantLock();
    this.running = new AtomicBoolean(false);
    this.executor = Executors.newSingleThreadExecutor();
    this.callback = VideoAttachableCallback.create();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(final FrameSource source) {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.running.get()) {
        return false;
      }

      this.source = source;
      this.running.set(true);
      this.executor.submit(this::runExecutorTask);

      return true;
    });
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.callback;
  }

  private void runExecutorTask() {
    final FrameSource source = requireNonNull(this.source);
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    try {
      while (this.running.get()) {
        final IntBuffer frame = source.getFrameSupplier().get();
        if (frame == null || frame.remaining() == 0) {
          this.waitForFrame();
          continue;
        }
        final int[] data = frame.array();
        final ImageBuffer image = ImageBuffer.buffer(data, width, height);
        VideoPipelineStep next = this.callback.retrieve();
        while (next != null) {
          next.process(image, metadata);
          next = next.next();
        }
        image.release();
      }
    } catch (final Exception e) {
      this.exceptionHandler.accept("Error processing frame in ImagePlayer", e);
    }
  }

  private void waitForFrame() {
    try {
      Thread.sleep(50);
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      final String msg = e.getMessage();
      requireNonNull(msg);
      this.exceptionHandler.accept(msg, e);
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
}
