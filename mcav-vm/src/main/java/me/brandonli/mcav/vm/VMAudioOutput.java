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
package me.brandonli.mcav.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.bytedeco.ffmpeg.global.avutil;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Hands the samples of the guest to the audio pipeline of the player on a thread of its own, so a slow pipeline never
 * holds up the audio connection.
 *
 * <p>Every chunk is held for {@value #DELAY_MILLIS} ms before it is handed over. QEMU sends the sound of the guest
 * about every 10 ms, but it refreshes the picture of its VNC display 30 ms after a change at the earliest, and later
 * when the screen was idle, so without the delay the sound would run ahead of the picture. At most
 * {@value #MAX_QUEUED_MILLIS} ms of samples wait; when more arrive, the oldest chunks are dropped, so a slow pipeline
 * never lets the sound fall behind by more than that. While the player is paused, samples are dropped instead of
 * queued, and pausing drops the queued ones, so no stale sound plays after a resume. A failing pipeline is reported
 * and the next chunk is handed over as usual.
 */
final class VMAudioOutput implements VMAudioClient.Sink, AutoCloseable {

  /**
   * How long every chunk is held before the pipeline gets it, in milliseconds.
   */
  static final int DELAY_MILLIS = 70;

  /**
   * The most sound that waits for the pipeline, the delay included, in milliseconds.
   */
  static final int MAX_QUEUED_MILLIS = DELAY_MILLIS + 60;

  /**
   * The format of the samples: 16-bit little-endian PCM in the rate and the channels of the audio pipeline.
   */
  static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    "pcm_s16le",
    AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * Byte.SIZE,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    avutil.AV_SAMPLE_FMT_S16
  );

  private static final int MAX_QUEUED_BYTES = (AudioFilter.SAMPLE_RATE / 1000) * MAX_QUEUED_MILLIS * AudioFilter.FRAME_SIZE;

  private final Supplier<AudioPipelineStep> pipeline;
  private final BiConsumer<String, Throwable> failures;
  private final LongSupplier clock;
  private final Deque<Chunk> queue;
  private volatile Thread thread;
  private int queuedBytes;
  private boolean paused;
  private boolean closed;

  private VMAudioOutput(
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock
  ) {
    this.pipeline = pipeline;
    this.failures = failures;
    this.clock = clock;
    this.queue = new ArrayDeque<>();
    // replaced by the running thread once the output exists
    this.thread = new Thread("mcav-vm-audio-output-not-started");
  }

  /**
   * Creates an output and starts its thread.
   *
   * @param pipeline gives the audio pipeline of the player for every chunk, so a pipeline attached later is used
   * @param failures receives a failure of the pipeline
   * @return the running output
   */
  static VMAudioOutput start(final Supplier<AudioPipelineStep> pipeline, final BiConsumer<String, Throwable> failures) {
    return start(pipeline, failures, System::nanoTime);
  }

  /**
   * Creates an output with another clock, so tests can check the delay, and starts its thread.
   *
   * @param pipeline gives the audio pipeline of the player for every chunk
   * @param failures receives a failure of the pipeline
   * @param clock    a monotonic clock in nanoseconds
   * @return the running output
   */
  static VMAudioOutput start(
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock
  ) {
    final VMAudioOutput output = new VMAudioOutput(pipeline, failures, clock);
    final Thread thread = new Thread(output::deliver, "mcav-vm-audio-output");
    thread.setDaemon(true);
    output.thread = thread;
    thread.start();
    return output;
  }

  /**
   * Queues a copy of samples, dropping the oldest queued ones beyond the limit, or drops them while paused.
   *
   * @param samples the buffer, which is reused by the caller
   * @param length  the number of bytes of samples at its start
   */
  @Override
  public synchronized void accept(final byte[] samples, final int length) {
    if (this.paused || this.closed || length == 0) {
      return;
    }
    final ByteBuffer copy = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    copy.put(samples, 0, length);
    copy.flip();
    final long due = this.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
    this.queue.addLast(new Chunk(copy, due));
    this.queuedBytes += length;
    // the newest chunk always stays, even when it alone is longer than the limit
    while (this.queuedBytes > MAX_QUEUED_BYTES && this.queue.size() > 1) {
      final Chunk dropped = this.queue.removeFirst();
      this.queuedBytes -= dropped.samples().remaining();
    }
    this.notifyAll();
  }

  /**
   * Drops the queued samples and every sample that arrives until {@link #resume()}.
   */
  synchronized void pause() {
    this.paused = true;
    this.queue.clear();
    this.queuedBytes = 0;
  }

  /**
   * Queues samples again.
   */
  synchronized void resume() {
    this.paused = false;
  }

  /**
   * Gets the number of bytes that wait for the pipeline.
   *
   * @return the queued bytes
   */
  synchronized int getQueuedBytes() {
    return this.queuedBytes;
  }

  /**
   * Gets the thread that hands the samples over, so tests can interrupt it.
   *
   * @return the thread
   */
  Thread getThread() {
    return this.thread;
  }

  /**
   * Waits until the oldest chunk is due, and takes it.
   *
   * @return the samples, or null once the output is closed
   * @throws InterruptedException if the thread is interrupted
   */
  private synchronized @Nullable ByteBuffer take() throws InterruptedException {
    while (!this.closed) {
      final Chunk next = this.queue.peekFirst();
      if (next == null) {
        this.wait();
        continue;
      }
      final long wait = next.due() - this.clock.getAsLong();
      if (wait <= 0) {
        this.queue.removeFirst();
        final ByteBuffer samples = next.samples();
        this.queuedBytes -= samples.remaining();
        return samples;
      }
      TimeUnit.NANOSECONDS.timedWait(this, wait);
    }
    return null;
  }

  private void deliver() {
    try {
      ByteBuffer next = this.take();
      while (next != null) {
        this.process(next);
        next = this.take();
      }
    } catch (final InterruptedException exception) {
      // nothing interrupts the thread but a test; it ends like a closed output
    }
  }

  private void process(final ByteBuffer samples) {
    try {
      final AudioPipelineStep step = this.pipeline.get();
      if (!step.isNoOp()) {
        step.processAll(samples, METADATA);
      }
    } catch (final RuntimeException | Error failure) {
      // filters are user code and native code, which can fail with any Error; only errors of the JVM are thrown
      ThrowableUtils.throwIfFatal(failure);
      this.failures.accept("Failed to process the audio of the virtual machine", failure);
    }
  }

  /**
   * Stops the thread, dropping the queued samples, and waits for it.
   */
  @Override
  public void close() {
    synchronized (this) {
      this.closed = true;
      this.queue.clear();
      this.queuedBytes = 0;
      this.notifyAll();
    }
    try {
      this.thread.join(VMAudioClient.HANDSHAKE_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * Samples and the time they are due at the pipeline.
   */
  private static final class Chunk {

    private final ByteBuffer samples;
    private final long due;

    Chunk(final ByteBuffer samples, final long due) {
      this.samples = samples;
      this.due = due;
    }

    ByteBuffer samples() {
      return this.samples;
    }

    long due() {
      return this.due;
    }
  }
}
