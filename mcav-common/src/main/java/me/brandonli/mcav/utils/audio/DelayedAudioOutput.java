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
 *
 * <p>A source that goes quiet sends nothing, so its sound would reach the players with every pause cut out. After
 * {@link #quiet()} the output hands over silence in real time instead: every gap from the end of the sound handed over
 * to the next samples due is filled in chunks of 20 ms, until samples that arrived after the
 * call are due, for {@value #SILENCE_MILLISECONDS} ms at most; a longer pause is not filled, and nothing is filled while the
 * output is paused.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class DelayedAudioOutput implements AutoCloseable {

  private static final String PCM_CODEC = "pcm_s16le";

  private static final String UNSTARTED_THREAD_NAME = "mcav-audio-output-not-started";

  private static final int MILLISECONDS_PER_SECOND = 1000;
  private static final long NANOSECONDS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

  private static final long MINIMUM_WAIT_NANOSECONDS = 1L;

  /**
   * The format of the samples: 16-bit little-endian PCM in the rate and the channels of the audio pipeline.
   *
   * @since 1.0.0
   */
  public static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of(
    PCM_CODEC,
    AudioFilter.SAMPLE_RATE * AudioFilter.FRAME_SIZE * Byte.SIZE,
    AudioFilter.SAMPLE_RATE,
    AudioFilter.CHANNELS,
    avutil.AV_SAMPLE_FMT_S16
  );

  /**
   * The name of the thread of every output, which hands the samples over.
   *
   * @since 1.0.0
   */
  public static final String THREAD_NAME = "mcav-audio-output";

  static final int JOIN_TIMEOUT_MILLISECONDS = 5_000;

  /**
   * How much silence follows {@link #quiet()} at most, in milliseconds.
   *
   * @since 1.0.0
   */
  public static final int SILENCE_MILLISECONDS = 2_000;

  private static final int SILENCE_CHUNK_MILLISECONDS = 20;

  private static final int BYTES_PER_MILLISECOND = (AudioFilter.SAMPLE_RATE / MILLISECONDS_PER_SECOND) * AudioFilter.FRAME_SIZE;

  /**
   * The longest queue whose size in bytes, 192 to a millisecond of 48 kHz stereo, still fits in an {@code int}.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_QUEUED_MILLISECONDS = Integer.MAX_VALUE / BYTES_PER_MILLISECOND;

  private final String source;
  private final long joinTimeoutMilliseconds;
  private final long delayNanoseconds;
  private final int maximumQueuedBytes;
  private final Supplier<AudioPipelineStep> pipeline;
  private final BiConsumer<String, Throwable> failures;
  private final LongSupplier clock;
  private final long silenceNanoseconds;
  private final Deque<Chunk> queue;
  private volatile Thread thread;
  private int queuedBytes;
  private boolean paused;
  private boolean closed;
  private long streamEnd;
  private long silenceLeftNanoseconds;

  private DelayedAudioOutput(
    final String source,
    final int delayMilliseconds,
    final int maximumQueuedMilliseconds,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock,
    final long joinTimeoutMilliseconds,
    final int silenceMilliseconds
  ) {
    this.source = source;
    this.joinTimeoutMilliseconds = joinTimeoutMilliseconds;
    this.delayNanoseconds = TimeUnit.MILLISECONDS.toNanos(delayMilliseconds);
    this.maximumQueuedBytes = BYTES_PER_MILLISECOND * maximumQueuedMilliseconds;
    this.pipeline = pipeline;
    this.failures = failures;
    this.clock = clock;
    this.silenceNanoseconds = TimeUnit.MILLISECONDS.toNanos(silenceMilliseconds);
    this.queue = new ArrayDeque<>();
    this.thread = new Thread(UNSTARTED_THREAD_NAME);
  }

  /**
   * Creates an output and starts its thread.
   *
   * @param source                    names the source in the failures it reports, such as {@code "the virtual machine"}
   * @param delayMilliseconds         how long every chunk is held before the pipeline gets it, in milliseconds
   * @param maximumQueuedMilliseconds the queue duration in milliseconds, strictly greater than {@code delayMilliseconds} and at most
   *                                  {@link #MAXIMUM_QUEUED_MILLISECONDS}, so its 192-bytes-per-millisecond size fits in an {@code int}
   * @param pipeline                  gives the audio pipeline of the player for every chunk, so a pipeline attached later is
   *                                  used
   * @param failures                  receives a failure of the pipeline
   * @return the running output
   * @throws IllegalArgumentException if the delay is negative or leaves no room in the queue, or the queue is longer
   *                                  than {@link #MAXIMUM_QUEUED_MILLISECONDS}
   * @throws NullPointerException     if {@code source}, {@code pipeline} or {@code failures} is null
   * @since 1.0.0
   */
  public static DelayedAudioOutput start(
    final String source,
    final int delayMilliseconds,
    final int maximumQueuedMilliseconds,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures
  ) {
    return start(
      source,
      delayMilliseconds,
      maximumQueuedMilliseconds,
      pipeline,
      failures,
      System::nanoTime,
      JOIN_TIMEOUT_MILLISECONDS,
      SILENCE_MILLISECONDS
    );
  }

  // Creates an output with another clock, so tests can check the delay, and starts its thread.
  @VisibleForTesting
  static DelayedAudioOutput start(
    final String source,
    final int delayMilliseconds,
    final int maximumQueuedMilliseconds,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock,
    final long joinTimeoutMilliseconds,
    final int silenceMilliseconds
  ) {
    Preconditions.checkNotNull(source, "Source must not be null!");
    Preconditions.checkNotNull(pipeline, "Pipeline must not be null!");
    Preconditions.checkNotNull(failures, "Failure handler must not be null!");
    Preconditions.checkArgument(delayMilliseconds >= 0, "Delay %s must not be negative!", delayMilliseconds);
    Preconditions.checkArgument(
      maximumQueuedMilliseconds > delayMilliseconds,
      "At most %s ms may wait, but the delay of %s ms leaves no room past it!",
      maximumQueuedMilliseconds,
      delayMilliseconds
    );
    Preconditions.checkArgument(
      maximumQueuedMilliseconds <= MAXIMUM_QUEUED_MILLISECONDS,
      "At most %s ms of sound may wait, but %s ms were asked for!",
      MAXIMUM_QUEUED_MILLISECONDS,
      maximumQueuedMilliseconds
    );

    final DelayedAudioOutput output = new DelayedAudioOutput(
      source,
      delayMilliseconds,
      maximumQueuedMilliseconds,
      pipeline,
      failures,
      clock,
      joinTimeoutMilliseconds,
      silenceMilliseconds
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
   * @param length  the byte count at the start of the array, from 0 through its length and a multiple of
   *                four for complete stereo frames; frame alignment is a caller precondition
   * @throws NullPointerException      if samples are actually queued and {@code samples} is null
   * @throws IllegalArgumentException  if samples are queued with a negative length
   * @throws IndexOutOfBoundsException if the requested byte range exceeds the array while queuing
   * @since 1.0.0
   */
  public synchronized void accept(final byte[] samples, final int length) {
    if (this.paused || this.closed || length == 0) {
      return;
    }

    final int kept = Math.min(length, this.maximumQueuedBytes);
    final ByteBuffer copy = ByteBuffer.allocate(kept).order(ByteOrder.LITTLE_ENDIAN);
    copy.put(samples, length - kept, kept);
    copy.flip();
    final long due = this.clock.getAsLong() + this.delayNanoseconds;
    final boolean endsQuiet = this.silenceLeftNanoseconds > 0;
    // Make room before addition so even the largest accepted queue cannot wrap its byte counter.
    while (this.queuedBytes > this.maximumQueuedBytes - kept) {
      final Chunk dropped = this.queue.removeFirst();
      this.queuedBytes -= dropped.samples().remaining();
    }

    this.queue.addLast(new Chunk(copy, due, endsQuiet));
    this.queuedBytes += kept;
    this.notifyAll();
  }

  /**
   * Drops the queued samples and every sample that arrives until {@link #resume()}.
   *
   * @since 1.0.0
   */
  public synchronized void pause() {
    this.paused = true;
    this.silenceLeftNanoseconds = 0;
    this.dropQueued();
  }

  /**
   * Tells the output that its source went quiet and sends nothing until it plays again: the pause is filled with
   * silence in real time, for {@value #SILENCE_MILLISECONDS} ms at most, until the samples that arrive next are due. Nothing
   * happens while the output is paused, and a closed output hands nothing over.
   *
   * @since 1.0.0
   */
  public synchronized void quiet() {
    if (this.paused) {
      return;
    }

    this.silenceLeftNanoseconds = this.silenceNanoseconds;
    this.notifyAll();
  }

  private void dropQueued() {
    this.queue.clear();
    this.queuedBytes = 0;
  }

  /**
   * Queues samples again after a pause. Calling this after close does not reopen the output.
   *
   * @since 1.0.0
   */
  public synchronized void resume() {
    this.paused = false;
  }

  private synchronized @Nullable ByteBuffer take() throws InterruptedException {
    while (!this.closed) {
      final long now = this.clock.getAsLong();
      final Chunk next = this.queue.peekFirst();
      if (next != null && next.due() - now <= 0) {
        return this.takeDue(next, now);
      }
      final ByteBuffer silence = this.takeSilence(now, next);
      if (silence != null) {
        return silence;
      }
      if (next == null && this.silenceLeftNanoseconds <= 0) {
        this.wait();
        continue;
      }
      final long nextDue = next == null ? Long.MAX_VALUE : next.due();
      final long wake = this.silenceLeftNanoseconds > 0 ? Math.min(nextDue, this.streamEnd) : nextDue;
      TimeUnit.NANOSECONDS.timedWait(this, Math.max(MINIMUM_WAIT_NANOSECONDS, wake - now));
    }

    return null;
  }

  private ByteBuffer takeDue(final Chunk next, final long now) {
    this.queue.removeFirst();
    final ByteBuffer samples = next.samples();
    this.queuedBytes -= samples.remaining();

    if (next.endsQuiet()) {
      this.silenceLeftNanoseconds = 0;
    }

    this.streamEnd = Math.max(this.streamEnd, now) + this.nanosecondsOf(samples.remaining());

    return samples;
  }

  private @Nullable ByteBuffer takeSilence(final long now, final @Nullable Chunk next) {
    if (this.silenceLeftNanoseconds <= 0 || this.streamEnd - now > 0) {
      return null;
    }

    final long start = Math.max(this.streamEnd, now);
    long nanoseconds = Math.min(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS), this.silenceLeftNanoseconds);
    if (next != null) {
      nanoseconds = Math.min(nanoseconds, next.due() - start);
    }

    final int bytes =
      ((int) ((nanoseconds * BYTES_PER_MILLISECOND) / NANOSECONDS_PER_MILLISECOND) / AudioFilter.FRAME_SIZE) * AudioFilter.FRAME_SIZE;
    if (bytes <= 0) {
      if (next == null) {
        this.silenceLeftNanoseconds = 0;
      }

      return null;
    }

    this.silenceLeftNanoseconds -= this.nanosecondsOf(bytes);
    this.streamEnd = start + this.nanosecondsOf(bytes);

    return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private long nanosecondsOf(final int bytes) {
    return (bytes * NANOSECONDS_PER_MILLISECOND) / BYTES_PER_MILLISECOND;
  }

  private void deliver() {
    try {
      ByteBuffer next = this.take();
      while (next != null) {
        this.sendToPipeline(next);
        next = this.take();
      }
    } catch (final InterruptedException exception) {
      // nothing interrupts the thread but a test; it ends like a closed output
    }
  }

  private void sendToPipeline(final ByteBuffer samples) {
    try {
      final AudioPipelineStep step = this.pipeline.get();
      if (!step.isNoOperation()) {
        step.processAll(samples, METADATA);
      }
    } catch (final RuntimeException | Error exception) {
      // filters are user code and native code, which can fail with any Error; only errors of the JVM are thrown
      ThrowableUtils.throwIfFatal(exception);
      this.report(exception);
    }
  }

  private void report(final Throwable failure) {
    try {
      this.failures.accept("The audio of %s could not be processed.".formatted(this.source), failure);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      // the exception handler is user code too; the sound goes on, and nothing else is left to tell
    }
  }

  /**
   * Stops the thread, dropping the queued samples, and waits for it; a pipeline that holds the thread is waited for
   * 5000 ms at most and then interrupted, so it can end on its own. Called from the pipeline
   * itself, it does not wait.
   *
   * @since 1.0.0
   */
  @Override
  public void close() {
    synchronized (this) {
      this.closed = true;
      this.dropQueued();
      this.notifyAll();
    }

    final Thread outputThread = this.thread;
    final Thread current = Thread.currentThread();
    if (outputThread.equals(current)) {
      return;
    }

    try {
      outputThread.join(this.joinTimeoutMilliseconds);
    } catch (final InterruptedException exception) {
      current.interrupt();
    }

    outputThread.interrupt();
  }

  @VisibleForTesting
  synchronized int getQueuedBytes() {
    return this.queuedBytes;
  }

  // Returns the thread that hands the samples over, so tests can interrupt it.
  @VisibleForTesting
  Thread getThread() {
    return this.thread;
  }

  private record Chunk(ByteBuffer samples, long due, boolean endsQuiet) {}
}
