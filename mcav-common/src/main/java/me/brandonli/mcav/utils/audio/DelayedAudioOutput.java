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
 * to the next samples due is filled in chunks of {@value #SILENCE_CHUNK_MILLIS} ms, until samples that arrived after the
 * call are due, for {@value #SILENCE_MILLIS} ms at most; a longer pause is not filled, and nothing is filled while the
 * output is paused.
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

  /** How much silence follows {@link #quiet()} at most, in milliseconds. */
  public static final int SILENCE_MILLIS = 2_000;

  /** How long each chunk of silence is, in milliseconds. */
  static final int SILENCE_CHUNK_MILLIS = 20;

  private static final int BYTES_PER_MILLISECOND = (AudioFilter.SAMPLE_RATE / 1000) * AudioFilter.FRAME_SIZE;

  /** The longest queue whose size in bytes, 192 to a millisecond of 48 kHz stereo, still fits in an {@code int}. */
  public static final int MAX_QUEUED_MILLIS = Integer.MAX_VALUE / ((AudioFilter.SAMPLE_RATE / 1000) * AudioFilter.FRAME_SIZE);

  private final String source;
  private final long joinTimeoutMillis;
  private final long delayNanos;
  private final int maxQueuedBytes;
  private final Supplier<AudioPipelineStep> pipeline;
  private final BiConsumer<String, Throwable> failures;
  private final LongSupplier clock;
  private final long silenceNanos;
  private final Deque<Chunk> queue;
  private volatile Thread thread;
  private int queuedBytes;
  private boolean paused;
  private boolean closed;
  private long streamEnd;
  private long silenceLeftNanos;

  private DelayedAudioOutput(
    final String source,
    final int delayMillis,
    final int maxQueuedMillis,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures,
    final LongSupplier clock,
    final long joinTimeoutMillis,
    final int silenceMillis
  ) {
    this.source = source;
    this.joinTimeoutMillis = joinTimeoutMillis;
    this.delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
    this.maxQueuedBytes = (AudioFilter.SAMPLE_RATE / 1000) * maxQueuedMillis * AudioFilter.FRAME_SIZE;
    this.pipeline = pipeline;
    this.failures = failures;
    this.clock = clock;
    this.silenceNanos = TimeUnit.MILLISECONDS.toNanos(silenceMillis);
    this.queue = new ArrayDeque<>();
    this.thread = new Thread("mcav-audio-output-not-started");
  }

  /**
   * Creates an output and starts its thread.
   *
   * @param source          names the source in the failures it reports, such as {@code "the virtual machine"}
   * @param delayMillis     how long every chunk is held before the pipeline gets it, in milliseconds
   * @param maxQueuedMillis the queue duration in milliseconds, strictly greater than {@code delayMillis} and at most
   *                        {@link #MAX_QUEUED_MILLIS}, so its 192-bytes-per-millisecond size fits in an {@code int}
   * @param pipeline        gives the audio pipeline of the player for every chunk, so a pipeline attached later is
   *                        used
   * @param failures        receives a failure of the pipeline
   * @return the running output
   * @throws IllegalArgumentException if the delay is negative or leaves no room in the queue, or the queue is longer
   *                                  than {@link #MAX_QUEUED_MILLIS}
   * @throws NullPointerException if {@code source}, {@code pipeline} or {@code failures} is null
   */
  public static DelayedAudioOutput start(
    final String source,
    final int delayMillis,
    final int maxQueuedMillis,
    final Supplier<AudioPipelineStep> pipeline,
    final BiConsumer<String, Throwable> failures
  ) {
    return start(source, delayMillis, maxQueuedMillis, pipeline, failures, System::nanoTime, JOIN_TIMEOUT_MILLIS, SILENCE_MILLIS);
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
   * @param silenceMillis     how much silence follows {@link #quiet()} at most, in milliseconds
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
    final long joinTimeoutMillis,
    final int silenceMillis
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
    Preconditions.checkArgument(
      maxQueuedMillis <= MAX_QUEUED_MILLIS,
      "At most %s ms of sound may wait, but %s ms were asked for",
      MAX_QUEUED_MILLIS,
      maxQueuedMillis
    );
    final DelayedAudioOutput output = new DelayedAudioOutput(
      source,
      delayMillis,
      maxQueuedMillis,
      pipeline,
      failures,
      clock,
      joinTimeoutMillis,
      silenceMillis
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
   * @throws NullPointerException if samples are actually queued and {@code samples} is null
   * @throws IllegalArgumentException if samples are queued with a negative length
   * @throws IndexOutOfBoundsException if the requested byte range exceeds the array while queuing
   */
  public synchronized void accept(final byte[] samples, final int length) {
    if (this.paused || this.closed || length == 0) {
      return;
    }
    final int kept = Math.min(length, this.maxQueuedBytes);
    final ByteBuffer copy = ByteBuffer.allocate(kept).order(ByteOrder.LITTLE_ENDIAN);
    copy.put(samples, length - kept, kept);
    copy.flip();
    final long due = this.clock.getAsLong() + this.delayNanos;
    final boolean endsQuiet = this.silenceLeftNanos > 0;
    // Make room before addition so even the largest accepted queue cannot wrap its byte counter.
    while (this.queuedBytes > this.maxQueuedBytes - kept) {
      final Chunk dropped = this.queue.removeFirst();
      this.queuedBytes -= dropped.samples().remaining();
    }
    this.queue.addLast(new Chunk(copy, due, endsQuiet));
    this.queuedBytes += kept;
    this.notifyAll();
  }

  /**
   * Drops the queued samples and every sample that arrives until {@link #resume()}.
   */
  public synchronized void pause() {
    this.paused = true;
    this.silenceLeftNanos = 0;
    this.dropQueued();
  }

  /**
   * Tells the output that its source went quiet and sends nothing until it plays again: the pause is filled with
   * silence in real time, for {@value #SILENCE_MILLIS} ms at most, until the samples that arrive next are due. Nothing
   * happens while the output is paused, and a closed output hands nothing over.
   */
  public synchronized void quiet() {
    if (this.paused) {
      return;
    }
    this.silenceLeftNanos = this.silenceNanos;
    this.notifyAll();
  }

  private void dropQueued() {
    this.queue.clear();
    this.queuedBytes = 0;
  }

  /**
   * Queues samples again after a pause. Calling this after close does not reopen the output.
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
      final long now = this.clock.getAsLong();
      final Chunk next = this.queue.peekFirst();
      if (next != null && next.due() - now <= 0) {
        this.queue.removeFirst();
        final ByteBuffer samples = next.samples();
        this.queuedBytes -= samples.remaining();
        if (next.endsQuiet()) {
          this.silenceLeftNanos = 0;
        }
        this.streamEnd = Math.max(this.streamEnd, now) + nanosOf(samples.remaining());
        return samples;
      }
      final ByteBuffer silence = this.silence(now, next);
      if (silence != null) {
        return silence;
      }
      if (next == null && this.silenceLeftNanos <= 0) {
        this.wait();
        continue;
      }
      final long nextDue = next == null ? Long.MAX_VALUE : next.due();
      final long wake = this.silenceLeftNanos > 0 ? Math.min(nextDue, this.streamEnd) : nextDue;
      TimeUnit.NANOSECONDS.timedWait(this, Math.max(1, wake - now));
    }
    return null;
  }

  /**
   * Makes the next chunk of silence of a quiet source, once the sound handed over has played: it ends when the next
   * samples are due, or after a chunk, or when the silence allowed is used up.
   *
   * @return the silence, or null if none is due now
   */
  private @Nullable ByteBuffer silence(final long now, final @Nullable Chunk next) {
    if (this.silenceLeftNanos <= 0 || this.streamEnd - now > 0) {
      return null;
    }
    final long start = Math.max(this.streamEnd, now);
    long nanos = Math.min(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLIS), this.silenceLeftNanos);
    if (next != null) {
      nanos = Math.min(nanos, next.due() - start);
    }
    final int bytes =
      ((int) ((nanos * BYTES_PER_MILLISECOND) / TimeUnit.MILLISECONDS.toNanos(1)) / AudioFilter.FRAME_SIZE) * AudioFilter.FRAME_SIZE;
    if (bytes <= 0) {
      if (next == null) {
        this.silenceLeftNanos = 0;
      }
      return null;
    }
    this.silenceLeftNanos -= nanosOf(bytes);
    this.streamEnd = start + nanosOf(bytes);
    return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static long nanosOf(final int bytes) {
    return (bytes * TimeUnit.MILLISECONDS.toNanos(1)) / BYTES_PER_MILLISECOND;
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
    } catch (final RuntimeException | Error handlerFailure) {
      ThrowableUtils.throwIfFatal(handlerFailure);
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
    current.interrupt();
  }

  /**
   * Samples and the time they are due at the pipeline.
   */
  private static final class Chunk {

    private final ByteBuffer samples;
    private final long due;
    private final boolean endsQuiet;

    Chunk(final ByteBuffer samples, final long due, final boolean endsQuiet) {
      this.samples = samples;
      this.due = due;
      this.endsQuiet = endsQuiet;
    }

    ByteBuffer samples() {
      return this.samples;
    }

    long due() {
      return this.due;
    }

    boolean endsQuiet() {
      return this.endsQuiet;
    }
  }
}
