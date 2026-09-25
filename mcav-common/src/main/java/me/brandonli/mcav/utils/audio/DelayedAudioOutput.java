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
package me.brandonli.mcav.utils.audio;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
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
 * Hands the sound of a source that produces it live, such as a virtual machine or a browser, to the audio pipeline of
 * its player on a thread of its own, so a slow pipeline never holds up the source. The samples are 16-bit
 * little-endian PCM in the rate and the channels of the audio pipeline ({@link #METADATA}).
 *
 * <p>Every chunk is held for a fixed delay before it is handed over, which lets the sound wait for a picture that
 * reaches the players later than the sound. At most the delay and a margin of samples wait; when more arrive, the
 * oldest samples are dropped, so a slow pipeline never lets the sound fall behind by more than that. While the output
 * is paused, samples are dropped instead of queued, and pausing drops the queued ones, so no stale sound plays after a
 * resume. A failing pipeline is reported and the next chunk is handed over as usual, even if reporting fails too.
 */
public final class DelayedAudioOutput implements AutoCloseable {

  /**
   * The format of the samples: 16-bit little-endian PCM in the rate and the channels of the audio pipeline.
   */
  public static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    "pcm_s16le",
    AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * Byte.SIZE,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    avutil.AV_SAMPLE_FMT_S16
  );

  /**
   * The name of the thread of every output, which hands the samples over.
   */
  public static final String THREAD_NAME = "mcav-audio-output";

  /**
   * How long closing waits for the thread at most, in milliseconds, should the pipeline hold it.
   */
  static final int JOIN_TIMEOUT_MILLIS = 5_000;

  private final String source;
  private final long joinTimeoutMillis;
  private final long delayNanos;
  private final int maxQueuedBytes;
  private final Supplier<AudioPipelineStep> pipeline;
  private final BiConsumer<String, Throwable> failures;
  private final LongSupplier clock;
  private final Deque<Chunk> queue;
  private volatile Thread thread;
  private int queuedBytes;
  private boolean paused;
  private boolean closed;

  private DelayedAudioOutput(
    final String source,
    final int delayMillis,
    final int maxQueuedMillis,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock,
    final long joinTimeoutMillis
  ) {
    this.source = source;
    this.joinTimeoutMillis = joinTimeoutMillis;
    this.delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
    this.maxQueuedBytes = (AudioFilter.SAMPLE_RATE / 1000) * maxQueuedMillis * AudioFilter.FRAME_SIZE;
    this.pipeline = pipeline;
    this.failures = failures;
    this.clock = clock;
    this.queue = new ArrayDeque<>();
    // replaced by the running thread once the output exists
    this.thread = new Thread("mcav-audio-output-not-started");
  }

  /**
   * Creates an output and starts its thread.
   *
   * @param source          names the source in the failures it reports, such as {@code "the virtual machine"}
   * @param delayMillis     how long every chunk is held before the pipeline gets it, in milliseconds
   * @param maxQueuedMillis the most sound that waits for the pipeline, the delay included, in milliseconds
   * @param pipeline        gives the audio pipeline of the player for every chunk, so a pipeline attached later is
   *                        used
   * @param failures        receives a failure of the pipeline
   * @return the running output
   * @throws IllegalArgumentException if the delay is negative or leaves no room in the queue
   */
  public static DelayedAudioOutput start(
    final String source,
    final int delayMillis,
    final int maxQueuedMillis,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures
  ) {
    return start(source, delayMillis, maxQueuedMillis, pipeline, failures, System::nanoTime, JOIN_TIMEOUT_MILLIS);
  }

  /**
   * Creates an output with another clock, so tests can check the delay, and starts its thread.
   *
   * @param source          names the source in the failures it reports
   * @param delayMillis     how long every chunk is held, in milliseconds
   * @param maxQueuedMillis the most sound that waits, the delay included, in milliseconds
   * @param pipeline        gives the audio pipeline of the player for every chunk
   * @param failures        receives a failure of the pipeline
   * @param clock             a monotonic clock in nanoseconds
   * @param joinTimeoutMillis how long closing waits for a pipeline that holds the thread, in milliseconds
   * @return the running output
   */
  @VisibleForTesting
  static DelayedAudioOutput start(
    final String source,
    final int delayMillis,
    final int maxQueuedMillis,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock,
    final long joinTimeoutMillis
  ) {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(pipeline, "Pipeline must not be null");
    Preconditions.checkNotNull(failures, "Failure handler must not be null");
    Preconditions.checkArgument(delayMillis >= 0, "The delay must not be negative but was %s", delayMillis);
    Preconditions.checkArgument(
      maxQueuedMillis > delayMillis,
      "At most %s ms may wait, which leaves no room past the delay of %s ms",
      maxQueuedMillis,
      delayMillis
    );
    final DelayedAudioOutput output = new DelayedAudioOutput(
      source,
      delayMillis,
      maxQueuedMillis,
      pipeline,
      failures,
      clock,
      joinTimeoutMillis
    );
    final Thread thread = new Thread(output::deliver, THREAD_NAME);
    thread.setDaemon(true);
    output.thread = thread;
    thread.start();
    return output;
  }

  /**
   * Queues a copy of samples, dropping the oldest queued ones beyond the limit, or drops them while paused.
   *
   * @param samples the buffer, which the caller may reuse
   * @param length  the number of bytes of samples at its start, whole frames
   */
  public synchronized void accept(final byte[] samples, final int length) {
    if (this.paused || this.closed || length == 0) {
      return;
    }
    // a chunk longer than the limit keeps its newest samples, whole frames as the limit is
    final int kept = Math.min(length, this.maxQueuedBytes);
    final ByteBuffer copy = ByteBuffer.allocate(kept).order(ByteOrder.LITTLE_ENDIAN);
    copy.put(samples, length - kept, kept);
    copy.flip();
    final long due = this.clock.getAsLong() + this.delayNanos;
    this.queue.addLast(new Chunk(copy, due));
    this.queuedBytes += kept;
    while (this.queuedBytes > this.maxQueuedBytes) {
      final Chunk dropped = this.queue.removeFirst();
      this.queuedBytes -= dropped.samples().remaining();
    }
    this.notifyAll();
  }

  /**
   * Drops the queued samples and every sample that arrives until {@link #resume()}.
   */
  public synchronized void pause() {
    this.paused = true;
    this.dropQueued();
  }

  private void dropQueued() {
    this.queue.clear();
    this.queuedBytes = 0;
  }

  /**
   * Queues samples again.
   */
  public synchronized void resume() {
    this.paused = false;
  }

  /**
   * Gets the number of bytes that wait for the pipeline.
   *
   * @return the queued bytes
   */
  @VisibleForTesting
  synchronized int getQueuedBytes() {
    return this.queuedBytes;
  }

  /**
   * Gets the thread that hands the samples over, so tests can interrupt it.
   *
   * @return the thread
   */
  @VisibleForTesting
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
      this.report(failure);
    }
  }

  private void report(final Throwable failure) {
    try {
      this.failures.accept("Failed to process the audio of " + this.source, failure);
    } catch (final RuntimeException handlerFailure) {
      // the exception handler is user code too; the sound goes on, and nothing else is left to tell
    }
  }

  /**
   * Stops the thread, dropping the queued samples, and waits for it; a pipeline that holds the thread is waited for
   * {@value #JOIN_TIMEOUT_MILLIS} ms at most and then interrupted, so it can end on its own. Called from the pipeline
   * itself, it does not wait.
   */
  @Override
  public void close() {
    synchronized (this) {
      this.closed = true;
      this.dropQueued();
      this.notifyAll();
    }
    final Thread current = this.thread;
    final Thread caller = Thread.currentThread();
    if (current.equals(caller)) {
      return;
    }
    try {
      current.join(this.joinTimeoutMillis);
    } catch (final InterruptedException exception) {
      caller.interrupt();
    }
    // a pipeline that still holds the thread is asked to let go of it; a thread that ended ignores this
    current.interrupt();
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
