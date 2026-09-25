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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VMAudioOutputTest {

  private static final int MAX_BYTES = (AudioFilter.SAMPLE_RATE / 1000) * VMAudioOutput.MAX_QUEUED_MILLIS * AudioFilter.FRAME_SIZE;

  private final List<byte[]> processed = new CopyOnWriteArrayList<>();
  private final List<OriginalAudioMetadata> formats = new CopyOnWriteArrayList<>();
  private final List<String> failures = new CopyOnWriteArrayList<>();
  private final CountDownLatch gate = new CountDownLatch(1);
  private volatile boolean blocking;
  private volatile AudioPipelineStep step = AudioPipelineStep.of(this::filter);
  private VMAudioOutput output = VMAudioOutput.start(
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
    huge[0] = 42;
    this.output.accept(huge, huge.length);
    assertEquals(huge.length, this.output.getQueuedBytes(), "the newest chunk stays, however long");
    this.blocking = false;
    this.gate.countDown();
    waitUntil(() -> this.processed.size() == 2);
    assertEquals(42, this.processed.get(1)[0]);
  }

  @Test
  void samplesAreHeldUntilTheyAreDue() throws InterruptedException {
    final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
    this.output.close();
    this.output = VMAudioOutput.start(() -> this.step, (message, failure) -> this.failures.add(message), now::get);
    this.output.accept(new byte[4], 4);
    Thread.sleep(3L * VMAudioOutput.DELAY_MILLIS);
    assertEquals(0, this.processed.size(), "not due before the clock passed the delay");
    now.set(TimeUnit.MILLISECONDS.toNanos(VMAudioOutput.DELAY_MILLIS));
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
    final VMAudioOutput empty = VMAudioOutput.start(() -> AudioPipelineStep.NO_OP, (message, failure) -> this.failures.add(message));
    empty.accept(new byte[4], 4);
    waitUntil(() -> empty.getQueuedBytes() == 0);
    empty.close();
    final VMAudioOutput failing = VMAudioOutput.start(
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
    final VMAudioOutput fatal = VMAudioOutput.start(
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
    this.output = VMAudioOutput.start(() -> this.step, (message, failure) -> this.failures.add(message));
    final Thread thread = this.output.getThread();
    thread.interrupt();
    thread.join(10_000L);
    assertFalse(thread.isAlive());
    final VMAudioOutput live = VMAudioOutput.start(() -> this.step, (message, failure) -> this.failures.add(message));
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
}
