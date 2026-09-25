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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DelayedAudioOutputTest {

  private static final int DELAY_MILLIS = 70;
  private static final int MAX_QUEUED_MILLIS = DELAY_MILLIS + 60;
  private static final int MAX_BYTES = (AudioFilter.SAMPLE_RATE / 1000) * MAX_QUEUED_MILLIS * AudioFilter.FRAME_SIZE;

  private final List<byte[]> processed = new CopyOnWriteArrayList<>();
  private final List<OriginalAudioMetadata> formats = new CopyOnWriteArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();
  private final CountDownLatch gate = new CountDownLatch(1);
  private volatile boolean blocking;
  private volatile AudioPipelineStep step = AudioPipelineStep.of(this::filter);
  private DelayedAudioOutput output = start(
    () -> this.step,
    (message, failure) -> this.failures.add(message + ": " + failure.getMessage())
  );

  private boolean filter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    if (this.blocking) {
      try {
        this.gate.await(10, TimeUnit.SECONDS);
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    this.processed.add(copy);
    this.formats.add(metadata);
    return true;
  }

  @AfterEach
  void closeOutput() {
    this.gate.countDown();
    this.output.close();
  }

  private static void waitUntil(final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void samplesReachThePipelineAsCopiesInThePipelinesFormat() {
    final byte[] samples = { 1, 0, 2, 0, 3, 0, 4, 0 };
    this.output.accept(samples, 8);
    samples[0] = 99;
    this.output.accept(samples, 0);
    waitUntil(() -> this.processed.size() == 1);
    assertEquals(1, this.processed.getFirst()[0], "the reused buffer of the caller is copied");
    final OriginalAudioMetadata metadata = this.formats.getFirst();
    assertEquals(48_000, metadata.getAudioSampleRate());
    assertEquals(2, metadata.getAudioChannels());
    assertEquals("pcm_s16le", metadata.getAudioCodec());
    assertEquals(48_000 * 4 * 8, metadata.getAudioBitrate());
  }

  @Test
  void aSlowPipelineMakesTheOldestSamplesGoAway() {
    this.blocking = true;
    this.output.accept(new byte[4], 4);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    final byte[] chunk = new byte[MAX_BYTES / 2];
    for (int count = 0; count < 5; count++) {
      chunk[0] = (byte) count;
      this.output.accept(chunk, chunk.length);
    }
    assertEquals(MAX_BYTES, this.output.getQueuedBytes(), "at most the limit waits");
    final byte[] huge = new byte[MAX_BYTES * 3];
    huge[huge.length - MAX_BYTES] = 41;
    huge[huge.length - MAX_BYTES - 4] = 40;
    huge[huge.length - 1] = 42;
    this.output.accept(huge, huge.length);
    assertEquals(MAX_BYTES, this.output.getQueuedBytes(), "a chunk longer than the limit keeps its newest samples");
    this.blocking = false;
    this.gate.countDown();
    waitUntil(() -> this.processed.size() == 2);
    final byte[] kept = this.processed.get(1);
    assertEquals(MAX_BYTES, kept.length);
    assertEquals(41, kept[0], "the kept samples start a whole frame into the newest limit");
    assertEquals(42, kept[MAX_BYTES - 1]);
  }

  @Test
  void anExceptionHandlerThatFailsDoesNotStopTheSound() {
    final List<byte[]> heard = new CopyOnWriteArrayList<>();
    final DelayedAudioOutput failing = start(
      () ->
        AudioPipelineStep.of((samples, metadata) -> {
          if (samples.get(0) == 1) {
            throw new IllegalStateException("filter broke");
          }
          final byte[] copy = new byte[samples.remaining()];
          samples.get(copy);
          heard.add(copy);
          return true;
        }),
      (message, failure) -> {
        throw new IllegalStateException("handler broke too");
      }
    );
    try {
      failing.accept(new byte[] { 1, 0, 0, 0 }, 4);
      failing.accept(new byte[] { 2, 0, 0, 0 }, 4);
      waitUntil(() -> heard.size() == 1);
      assertEquals(2, heard.getFirst()[0], "the chunk after the failures still plays");
    } finally {
      failing.close();
    }
  }

  @Test
  void aFilterThatClosesTheOutputDoesNotWaitForItself() {
    final java.util.concurrent.atomic.AtomicReference<DelayedAudioOutput> self = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicLong closeNanos = new java.util.concurrent.atomic.AtomicLong(-1);
    final DelayedAudioOutput closing = start(
      () ->
        AudioPipelineStep.of((samples, metadata) -> {
          final long start = System.nanoTime();
          self.get().close();
          closeNanos.set(System.nanoTime() - start);
          return true;
        }),
      (message, failure) -> this.failures.add(message)
    );
    self.set(closing);
    closing.accept(new byte[4], 4);
    waitUntil(() -> closeNanos.get() >= 0);
    assertTrue(closeNanos.get() < TimeUnit.SECONDS.toNanos(1), "closed without waiting for its own thread");
    assertEquals(List.of(), this.failures);
  }

  @Test
  void theOutputRunsAsADaemonAndClosingDropsWhatWaits() {
    assertTrue(this.output.getThread().isDaemon(), "the sound never keeps the JVM alive");
    assertEquals(DelayedAudioOutput.THREAD_NAME, this.output.getThread().getName());
    this.blocking = true;
    this.output.accept(new byte[4], 4);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    this.output.accept(new byte[8], 8);
    assertEquals(8, this.output.getQueuedBytes());
    // the delivery thread is still inside the pipeline when the close begins, and leaves it a moment later
    final CompletableFuture<Void> released = CompletableFuture.runAsync(
      this.gate::countDown,
      CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)
    );
    this.output.close();
    assertFalse(this.output.getThread().isAlive(), "closing waits for the delivery thread");
    assertEquals(0, this.output.getQueuedBytes(), "a closed output holds no sound");
    released.join();
  }

  @Test
  void samplesAreHeldUntilTheyAreDue() throws InterruptedException {
    final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
    this.output.close();
    this.output = DelayedAudioOutput.start(
      "the virtual machine",
      DELAY_MILLIS,
      MAX_QUEUED_MILLIS,
      () -> this.step,
      (message, failure) -> this.failures.add(message),
      now::get
    );
    this.output.accept(new byte[4], 4);
    Thread.sleep(3L * DELAY_MILLIS);
    assertEquals(0, this.processed.size(), "not due before the clock passed the delay");
    now.set(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS));
    waitUntil(() -> this.processed.size() == 1);
  }

  @Test
  void aPausedOutputDropsSamples() {
    this.blocking = true;
    this.output.accept(new byte[4], 4);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    this.output.accept(new byte[8], 8);
    this.output.pause();
    assertEquals(0, this.output.getQueuedBytes(), "pausing drops what waits");
    this.output.accept(new byte[8], 8);
    assertEquals(0, this.output.getQueuedBytes(), "and what arrives");
    this.output.resume();
    this.output.accept(new byte[] { 5, 0, 5, 0 }, 4);
    this.blocking = false;
    this.gate.countDown();
    waitUntil(() -> this.processed.size() == 2);
    assertEquals(5, this.processed.get(1)[0]);
  }

  @Test
  void aFailingPipelineIsReportedAndAnEmptyOneSkipped() {
    final DelayedAudioOutput empty = start(() -> AudioPipelineStep.NO_OP, (message, failure) -> this.failures.add(message));
    empty.accept(new byte[4], 4);
    waitUntil(() -> empty.getQueuedBytes() == 0);
    empty.close();
    final DelayedAudioOutput failing = start(
      () ->
        AudioPipelineStep.of((samples, metadata) -> {
          throw new IllegalStateException("filter broke");
        }),
      (message, failure) -> this.failures.add(message + ": " + failure.getMessage())
    );
    failing.accept(new byte[4], 4);
    waitUntil(() -> !this.failures.isEmpty());
    failing.close();
    assertEquals(List.of("Failed to process the audio of the virtual machine: filter broke"), this.failures);
    final DelayedAudioOutput fatal = start(
      () ->
        AudioPipelineStep.of((samples, metadata) -> {
          throw new OutOfMemoryError("fatal");
        }),
      (message, failure) -> this.failures.add(message)
    );
    fatal.accept(new byte[4], 4);
    waitUntil(() -> !fatal.getThread().isAlive());
    assertEquals(1, this.failures.size(), "an error of the virtual machine ends the thread instead");
  }

  @Test
  void aClosedOrInterruptedOutputEnds() throws InterruptedException {
    this.output.close();
    assertFalse(this.output.getThread().isAlive());
    this.output.accept(new byte[4], 4);
    assertEquals(0, this.output.getQueuedBytes());
    this.output = start(() -> this.step, (message, failure) -> this.failures.add(message));
    final Thread thread = this.output.getThread();
    thread.interrupt();
    thread.join(10_000L);
    assertFalse(thread.isAlive());
    final DelayedAudioOutput live = start(() -> this.step, (message, failure) -> this.failures.add(message));
    Thread.currentThread().interrupt();
    try {
      live.close();
      assertTrue(Thread.currentThread().isInterrupted(), "an interrupted close keeps the interrupt");
    } finally {
      Thread.interrupted();
    }
    live.getThread().join(10_000L);
    assertFalse(live.getThread().isAlive());
  }

  @Test
  void anOutputNeedsADelayAndRoomPastIt() {
    final Supplier<AudioPipelineStep> pipeline = () -> this.step;
    final BiConsumer<String, Throwable> ignored = (message, failure) -> {};
    final IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () ->
      DelayedAudioOutput.start("a source", -1, 10, pipeline, ignored)
    );
    assertEquals("The delay must not be negative but was -1", negative.getMessage());
    final IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () ->
      DelayedAudioOutput.start("a source", 10, 10, pipeline, ignored)
    );
    assertEquals("At most 10 ms may wait, which leaves no room past the delay of 10 ms", full.getMessage());
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start(null, 0, 1, pipeline, ignored));
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start("a source", 0, 1, null, ignored));
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start("a source", 0, 1, pipeline, null));
    final DelayedAudioOutput immediate = DelayedAudioOutput.start("a source", 0, 1, pipeline, ignored);
    try {
      immediate.accept(new byte[] { 7, 0, 7, 0 }, 4);
      waitUntil(() -> this.processed.size() == 1);
      assertEquals(7, this.processed.getFirst()[0], "no delay hands the samples over at once");
      immediate.accept(new byte[400], 400);
      assertTrue(immediate.getQueuedBytes() <= 192, "1 ms holds 48 frames of 4 bytes");
    } finally {
      immediate.close();
    }
  }

  private static DelayedAudioOutput start(final Supplier<AudioPipelineStep> pipeline, final BiConsumer<String, Throwable> failures) {
    return DelayedAudioOutput.start("the virtual machine", DELAY_MILLIS, MAX_QUEUED_MILLIS, pipeline, failures);
  }
}
