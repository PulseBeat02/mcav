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

import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.frame.FrameSource;
import me.brandonli.mcav.media.source.frame.SampleSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link ImagePlayer}. A dedicated thread asks the source for a frame once per frame period, runs the
 * pipeline on it, and reports failures of the source and the filters to the exception handler without stopping
 * playback. When the source itself cannot be set up, the thread reports it and ends, and the player can be started
 * again.
 */
public final class ImagePlayerImpl implements ImagePlayer {

  private static final long STOP_TIMEOUT_MILLIS = 2_000L;

  private final VideoAttachableCallback callback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final AtomicBoolean playing;
  private final AtomicBoolean released;

  private @Nullable Thread thread;

  ImagePlayerImpl() {
    this.callback = VideoAttachableCallback.create();
    this.exceptionHandler = ExceptionHandler.createDefault();
    this.lock = new ReentrantLock();
    this.playing = new AtomicBoolean(false);
    this.released = new AtomicBoolean(false);
  }

  /**
   * Starts delivering the frames of a source on a new thread.
   *
   * @param source the source, whose frame rate must be positive and finite
   * @return true if playback started, false if the player is already playing or was released
   * @throws IllegalArgumentException if the frame rate of the source is not positive and finite
   */
  @Override
  public boolean start(final FrameSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final float frameRate = source.getFrameRate();
    final boolean finite = Float.isFinite(frameRate);
    Preconditions.checkArgument(frameRate > 0 && finite, "Frame rate must be positive and finite but was %s", frameRate);
    this.lock.lock();
    try {
      final boolean gone = this.released.get();
      if (gone) {
        return false;
      }
      final boolean started = this.playing.compareAndSet(false, true);
      if (!started) {
        return false;
      }
      final Runnable loop = () -> this.run(source);
      final Thread worker = new Thread(loop, "mcav-image-player");
      worker.setDaemon(true);
      this.thread = worker;
      worker.start();
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Plays a source until the player is released or the source fails, and marks the player as stopped either way.
   */
  private void run(final FrameSource source) {
    try {
      this.play(source);
    } catch (final RuntimeException exception) {
      this.report("Failed to play the frame source", exception);
    } finally {
      this.playing.set(false);
    }
  }

  private void play(final FrameSource source) {
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final float frameRate = source.getFrameRate();
    final long secondNanos = TimeUnit.SECONDS.toNanos(1);
    final long periodNanos = (long) (secondNanos / frameRate);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height, frameRate);
    final SampleSupplier supplier = source.supplyFrameSamples();
    final FrameLoop loop = new FrameLoop(supplier, width, height, metadata);
    loop.run(periodNanos);
  }

  private void report(final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, failure);
  }

  /**
   * Stops playback and releases the player. The frame thread is woken up and awaited for up to two seconds; a
   * released player cannot be started again.
   *
   * @return true if the player was released, false if it was already released
   */
  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }

      this.playing.set(false);
      final Thread worker = this.thread;
      if (worker != null) {
        LockSupport.unpark(worker);
        this.join(worker);
        this.thread = null;
      }
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private void join(final Thread worker) {
    try {
      worker.join(STOP_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * Gets the slot that holds the video pipeline, which can be swapped while playing.
   *
   * @return the video pipeline slot
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.callback;
  }

  /**
   * Gets the handler that receives failures of the source and the filters, which logs them by default.
   *
   * @return the current handler
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler.getExceptionHandler();
  }

  /**
   * Sets the handler that receives failures of the source and the filters. The handler is called on the frame thread,
   * so it must be fast and must not throw.
   *
   * @param exceptionHandler the new handler
   * @throws NullPointerException if the handler is null
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.exceptionHandler.setExceptionHandler(exceptionHandler);
  }

  /**
   * The frame loop of one playback, which owns the image buffer the frames are copied into.
   */
  private final class FrameLoop {

    private final SampleSupplier supplier;
    private final int width;
    private final int height;
    private final OriginalVideoMetadata metadata;

    private @Nullable ImageBuffer buffer;

    FrameLoop(final SampleSupplier supplier, final int width, final int height, final OriginalVideoMetadata metadata) {
      this.supplier = supplier;
      this.width = width;
      this.height = height;
      this.metadata = metadata;
    }

    void run(final long periodNanos) {
      long deadline = System.nanoTime();
      try {
        while (ImagePlayerImpl.this.playing.get()) {
          deadline += periodNanos;
          this.renderFrame();
          final long remaining = deadline - System.nanoTime();
          if (remaining > 0) {
            LockSupport.parkNanos(remaining);
          } else {
            deadline = System.nanoTime();
          }
        }
      } finally {
        final ImageBuffer current = this.buffer;
        if (current != null) {
          current.release();
        }
      }
    }

    /**
     * Renders one frame. Failures of the source and the filters are reported and skip only this frame.
     */
    private void renderFrame() {
      try {
        final int[] pixels = this.supplier.getFrameSamples();
        final int expected = this.width * this.height;
        if (pixels.length != expected) {
          return;
        }
        final ImageBuffer filled = this.fill(pixels);
        final VideoPipelineStep pipeline = ImagePlayerImpl.this.callback.retrieve();
        pipeline.processAll(filled, this.metadata);
      } catch (final RuntimeException exception) {
        ImagePlayerImpl.this.report("Failed to render a frame", exception);
      }
    }

    private ImageBuffer fill(final int[] pixels) {
      final ImageBuffer existing = this.buffer;
      if (existing != null) {
        existing.updateArgb(pixels, this.width, this.height);
        return existing;
      }
      final ImageBuffer created = ImageBuffer.buffer(pixels, this.width, this.height);
      this.buffer = created;
      return created;
    }
  }
}
