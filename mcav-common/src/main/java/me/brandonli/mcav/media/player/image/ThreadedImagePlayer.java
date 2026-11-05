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
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
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
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class ThreadedImagePlayer implements ImagePlayer {

  private static final String THREAD_NAME = "mcav-image-player";

  private static final long STOP_TIMEOUT_MILLISECONDS = 2_000L;

  private static final long NANOSECONDS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private final LongSupplier clock;
  private final LongConsumer park;
  private final VideoAttachableCallback callback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final AtomicBoolean playing;
  private final AtomicBoolean released;

  private @Nullable Thread thread;

  ThreadedImagePlayer() {
    this(System::nanoTime, LockSupport::parkNanos);
  }

  ThreadedImagePlayer(final LongSupplier clock, final LongConsumer park) {
    this.clock = clock;
    this.park = park;
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
   * @throws NullPointerException     if {@code source} is null
   * @since 1.0.0
   */
  @Override
  public boolean start(final FrameSource source) {
    Preconditions.checkNotNull(source, "Source must not be null!");

    final float frameRate = source.getFrameRate();
    final boolean finite = Float.isFinite(frameRate);
    Preconditions.checkArgument(frameRate > 0 && finite, "Frame rate %s must be positive and finite!", frameRate);

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
      final Thread worker = new Thread(loop, THREAD_NAME);
      worker.setDaemon(true);
      this.thread = worker;
      worker.start();

      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private void run(final FrameSource source) {
    try {
      this.play(source);
    } catch (final RuntimeException exception) {
      this.report("Failed to play the frame source.", exception);
    } finally {
      this.playing.set(false);
    }
  }

  private void play(final FrameSource source) {
    final int width = source.getFrameWidth();
    final int height = source.getFrameHeight();
    final float frameRate = source.getFrameRate();
    final long periodNanoseconds = (long) (NANOSECONDS_PER_SECOND / frameRate);
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height, frameRate);
    final SampleSupplier supplier = source.getSampleSupplier();
    final FrameLoop loop = new FrameLoop(supplier, width, height, metadata);
    loop.run(periodNanoseconds);
  }

  private void report(final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, failure);
  }

  /**
   * Stops playback and releases the player. An external caller interrupts and awaits the frame thread for up to
   * two seconds, without holding the player lock. A pipeline can release its own player without waiting for itself.
   * A callback that ignores interruption may outlive the wait; a released player cannot be started again.
   *
   * @return true if the player was released, false if it was already released
   * @since 1.0.0
   */
  @Override
  public boolean release() {
    final Thread worker;
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }

      this.playing.set(false);
      worker = this.thread;
      this.thread = null;
    } finally {
      this.lock.unlock();
    }

    if (worker != null) {
      LockSupport.unpark(worker);
      final Thread caller = Thread.currentThread();
      final boolean isWorkerThread = caller.equals(worker);
      if (!isWorkerThread) {
        worker.interrupt();
        this.join(worker);
      }
    }

    return true;
  }

  private void join(final Thread worker) {
    try {
      worker.join(STOP_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * Sets the handler that receives failures of the source and the filters. The handler is called on the frame thread,
   * so it must be fast and must not throw.
   *
   * @param exceptionHandler the new handler
   * @throws NullPointerException if the handler is null
   * @since 1.0.0
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null!");

    this.exceptionHandler.setExceptionHandler(exceptionHandler);
  }

  /**
   * Returns the slot that holds the video pipeline, which can be swapped while playing.
   *
   * @return the video pipeline slot
   * @since 1.0.0
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.callback;
  }

  /**
   * Returns the handler that receives failures of the source and the filters, which logs them by default.
   *
   * @return the current handler
   * @since 1.0.0
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler.getExceptionHandler();
  }

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

    void run(final long periodNanoseconds) {
      final long start = ThreadedImagePlayer.this.clock.getAsLong();
      try {
        this.renderUntilStopped(start, periodNanoseconds);
      } finally {
        this.releaseBuffer();
      }
    }

    private void renderUntilStopped(final long start, final long periodNanoseconds) {
      long deadline = start;
      while (ThreadedImagePlayer.this.playing.get()) {
        deadline += periodNanoseconds;
        this.renderFrame();
        deadline = this.waitForDeadline(deadline);
      }
    }

    private long waitForDeadline(final long deadline) {
      final long now = ThreadedImagePlayer.this.clock.getAsLong();
      final long remaining = deadline - now;
      if (remaining > 0) {
        ThreadedImagePlayer.this.park.accept(remaining);
        return deadline;
      }

      return ThreadedImagePlayer.this.clock.getAsLong();
    }

    private void releaseBuffer() {
      final ImageBuffer current = this.buffer;
      if (current != null) {
        current.release();
      }
    }

    // Failures of the source and the filters are reported and skip only this frame.
    private void renderFrame() {
      try {
        final int[] pixels = this.supplier.getFrameSamples();
        final int expected = this.width * this.height;
        if (pixels.length != expected) {
          return;
        }

        final ImageBuffer filled = this.fill(pixels);
        final VideoPipelineStep pipeline = ThreadedImagePlayer.this.callback.retrieve();
        pipeline.processAll(filled, this.metadata);
      } catch (final RuntimeException exception) {
        ThreadedImagePlayer.this.report("Failed to render a frame.", exception);
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
