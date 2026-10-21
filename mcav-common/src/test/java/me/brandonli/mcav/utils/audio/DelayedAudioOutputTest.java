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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

class DelayedAudioOutputTest {

  private static final int DELAY_MILLISECONDS = 70;
  private static final int MAXIMUM_QUEUED_MILLISECONDS = DELAY_MILLISECONDS + 60;
  private static final long FRAME_NANOSECONDS = 20_833;
  private static final int MAXIMUM_BYTES = (AudioFilter.SAMPLE_RATE / 1000) * MAXIMUM_QUEUED_MILLISECONDS * AudioFilter.FRAME_SIZE;

  private static final int BYTES_PER_MILLISECOND = 192;
  private static final int TWO_FRAMES_BYTES = 8;
  private static final int SOUND_CHUNK_BYTES = 1920;

  private static final int SILENCE_LIMIT_MILLISECONDS = 60;
  private static final int LONG_SILENCE_LIMIT_MILLISECONDS = 2000;
  private static final int SILENCE_CHUNK_MILLISECONDS = 20;
  private static final int LATER_MILLISECONDS = 400;
  private static final int LATER_DUE_MILLISECONDS = 470;

  private static final long AWAIT_SECONDS = 10L;
  private static final long JOIN_MILLISECONDS = 10_000L;

  private static final String SOURCE_NAME = "the virtual machine";
  private static final String VALIDATED_SOURCE_NAME = "a source";

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
        this.gate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
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
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out!");
      }
      Thread.onSpinWait();
    }
  }

  @Test
  void shouldDeliverSamplesToThePipelineAsCopiesInThePipelinesFormat() {
    final byte[] samples = { 1, 0, 2, 0, 3, 0, 4, 0 };
    this.output.accept(samples, TWO_FRAMES_BYTES);
    samples[0] = 99;
    this.output.accept(samples, 0);
    waitUntil(() -> this.processed.size() == 1);
    final byte firstSample = this.processed.getFirst()[0];
    assertEquals(1, firstSample, "the reused buffer of the caller is copied");

    final OriginalAudioMetadata metadata = this.formats.getFirst();
    final int sampleRate = metadata.getAudioSampleRate();
    assertEquals(48_000, sampleRate);

    final int channels = metadata.getAudioChannels();
    assertEquals(2, channels);

    final String codec = metadata.getAudioCodec();
    assertEquals("pcm_s16le", codec);

    final int bitrate = metadata.getAudioBitrate();
    assertEquals(48_000 * 4 * 8, bitrate);
  }

  @Test
  void shouldDropTheOldestSamplesForASlowPipeline() {
    this.blocking = true;
    this.output.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    final byte[] chunk = new byte[MAXIMUM_BYTES / 2];
    for (int count = 0; count < 5; count++) {
      chunk[0] = (byte) count;
      this.output.accept(chunk, chunk.length);
    }

    final int queuedBytes = this.output.getQueuedBytes();
    assertEquals(MAXIMUM_BYTES, queuedBytes, "at most the limit waits");

    final byte[] huge = new byte[MAXIMUM_BYTES * 3];
    huge[huge.length - MAXIMUM_BYTES] = 41;
    huge[huge.length - MAXIMUM_BYTES - AudioFilter.FRAME_SIZE] = 40;
    huge[huge.length - 1] = 42;
    this.output.accept(huge, huge.length);
    final int queuedAfterHuge = this.output.getQueuedBytes();
    assertEquals(MAXIMUM_BYTES, queuedAfterHuge, "a chunk longer than the limit keeps its newest samples");

    this.blocking = false;
    this.gate.countDown();
    waitUntil(() -> this.processed.size() == 2);
    final byte[] kept = this.processed.get(1);
    assertEquals(MAXIMUM_BYTES, kept.length);

    final byte firstKept = kept[0];
    assertEquals(41, firstKept, "the kept samples start a whole frame into the newest limit");

    final byte lastKept = kept[MAXIMUM_BYTES - 1];
    assertEquals(42, lastKept);
  }

  @Test
  void shouldNotStopTheSoundWhenTheExceptionHandlerFails() {
    final List<byte[]> heard = new CopyOnWriteArrayList<>();
    final DelayedAudioOutput failing = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.breakOnMarkedChunkOrHear(samples, heard)),
      (message, failure) -> this.breakHandler()
    );
    try {
      failing.accept(new byte[] { 1, 0, 0, 0 }, AudioFilter.FRAME_SIZE);
      failing.accept(new byte[] { 2, 0, 0, 0 }, AudioFilter.FRAME_SIZE);
      waitUntil(() -> heard.size() == 1);
      final byte firstHeard = heard.getFirst()[0];
      assertEquals(2, firstHeard, "the chunk after the failures still plays");
    } finally {
      failing.close();
    }
  }

  private boolean breakOnMarkedChunkOrHear(final ByteBuffer samples, final List<byte[]> heard) {
    if (samples.get(0) == 1) {
      throw new IllegalStateException("filter broke!");
    }

    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    heard.add(copy);

    return true;
  }

  private void breakHandler() {
    throw new IllegalStateException("handler broke too!");
  }

  @ParameterizedTest
  @EnumSource(ReporterError.class)
  void shouldNotStopTheSoundOnANonfatalErrorInTheExceptionHandler(final ReporterError reporterError) throws InterruptedException {
    final boolean linkage = reporterError == ReporterError.LINKAGE;
    final AtomicReference<byte[]> heard = new AtomicReference<>();
    final AtomicReference<Throwable> escaped = new AtomicReference<>();
    final CountDownLatch settled = new CountDownLatch(1);
    final Error reportFailure = linkage ? new NoClassDefFoundError("reporter unavailable!") : new AssertionError("reporter failed!");
    final DelayedAudioOutput failing = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.failOnMarkedChunkOrSettleHeard(samples, heard, settled)),
      (message, failure) -> this.throwReportFailure(reportFailure)
    );
    failing.getThread().setUncaughtExceptionHandler((thread, failure) -> this.settleEscapedFailure(failure, escaped, settled));
    try {
      failing.accept(new byte[] { 1, 0, 0, 0 }, AudioFilter.FRAME_SIZE);
      failing.accept(new byte[] { 2, 3, 4, 5 }, AudioFilter.FRAME_SIZE);
      final boolean observed = settled.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(observed, "delivery or an escaped failure must be observed");

      final Throwable escapedFailure = escaped.get();
      assertNull(escapedFailure, "a recoverable reporting error must stay inside the delivery boundary");

      final byte[] heardSamples = heard.get();
      assertArrayEquals(new byte[] { 2, 3, 4, 5 }, heardSamples);

      final boolean alive = failing.getThread().isAlive();
      assertTrue(alive);
    } finally {
      failing.close();
    }

    final boolean aliveAfterClose = failing.getThread().isAlive();
    assertFalse(aliveAfterClose);
  }

  private boolean failOnMarkedChunkOrSettleHeard(
    final ByteBuffer samples,
    final AtomicReference<byte[]> heard,
    final CountDownLatch settled
  ) {
    if (samples.get(0) == 1) {
      throw new IllegalStateException("filter failed!");
    }

    final byte[] copy = new byte[samples.remaining()];
    samples.get(copy);
    heard.set(copy);
    settled.countDown();

    return true;
  }

  private void throwReportFailure(final Error reportFailure) {
    throw reportFailure;
  }

  private void settleEscapedFailure(final Throwable failure, final AtomicReference<Throwable> escaped, final CountDownLatch settled) {
    escaped.set(failure);
    settled.countDown();
  }

  @Test
  void shouldEndTheOutputBeforeTheNextChunkOnAFatalErrorInTheExceptionHandler() throws InterruptedException {
    final AtomicReference<Throwable> escaped = new AtomicReference<>();
    final AtomicBoolean continued = new AtomicBoolean();
    final CountDownLatch settled = new CountDownLatch(1);
    final OutOfMemoryError reportFailure = new OutOfMemoryError("synthetic reporter failure!");
    final DelayedAudioOutput failing = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.failOnMarkedChunkOrSettleContinued(samples, continued, settled)),
      (message, failure) -> this.throwReportFailure(reportFailure)
    );
    failing.getThread().setUncaughtExceptionHandler((thread, failure) -> this.settleEscapedFailure(failure, escaped, settled));
    try {
      failing.accept(new byte[] { 1, 0, 0, 0 }, AudioFilter.FRAME_SIZE);
      failing.accept(new byte[] { 2, 0, 0, 0 }, AudioFilter.FRAME_SIZE);
      final boolean observed = settled.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(observed, "the failure or the next chunk must be observed");

      final Throwable escapedFailure = escaped.get();
      assertSame(reportFailure, escapedFailure, "a fatal reporting error must reach the thread's uncaught handler");

      final boolean delivered = continued.get();
      assertFalse(delivered, "a fatal reporting error ends delivery before the next chunk");

      failing.getThread().join(JOIN_MILLISECONDS);
      final boolean alive = failing.getThread().isAlive();
      assertFalse(alive);
    } finally {
      failing.close();
    }
  }

  private boolean failOnMarkedChunkOrSettleContinued(
    final ByteBuffer samples,
    final AtomicBoolean continued,
    final CountDownLatch settled
  ) {
    if (samples.get(0) == 1) {
      throw new IllegalStateException("filter failed!");
    }

    continued.set(true);
    settled.countDown();

    return true;
  }

  @Test
  void shouldNotMakeAFilterThatClosesTheOutputWaitForItself() {
    final AtomicReference<DelayedAudioOutput> self = new AtomicReference<>();
    final AtomicLong closeNanoseconds = new AtomicLong(-1);
    final DelayedAudioOutput closing = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.closeItselfAndMeasure(self, closeNanoseconds)),
      (message, failure) -> this.failures.add(message)
    );
    self.set(closing);
    closing.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> closeNanoseconds.get() >= 0);
    final boolean closedQuickly = closeNanoseconds.get() < TimeUnit.SECONDS.toNanos(1);
    assertTrue(closedQuickly, "closed without waiting for its own thread");
    assertEquals(List.of(), this.failures);
  }

  private boolean closeItselfAndMeasure(final AtomicReference<DelayedAudioOutput> self, final AtomicLong closeNanoseconds) {
    final long start = System.nanoTime();
    self.get().close();
    closeNanoseconds.set(System.nanoTime() - start);

    return true;
  }

  @Test
  void shouldRunTheOutputAsADaemonAndDropWhatWaitsWhenClosing() {
    final boolean daemon = this.output.getThread().isDaemon();
    assertTrue(daemon, "the sound never keeps the JVM alive");

    final String threadName = this.output.getThread().getName();
    assertEquals(DelayedAudioOutput.THREAD_NAME, threadName);

    this.blocking = true;
    this.output.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    this.output.accept(new byte[TWO_FRAMES_BYTES], TWO_FRAMES_BYTES);
    final int queuedBytes = this.output.getQueuedBytes();
    assertEquals(TWO_FRAMES_BYTES, queuedBytes);

    final CompletableFuture<Void> released = CompletableFuture.runAsync(
      this.gate::countDown,
      CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS)
    );
    this.output.close();
    final boolean alive = this.output.getThread().isAlive();
    assertFalse(alive, "closing waits for the delivery thread");

    final int queuedAfterClose = this.output.getQueuedBytes();
    assertEquals(0, queuedAfterClose, "a closed output holds no sound");

    released.join();
  }

  @Test
  void shouldInterruptAPipelineThatStillHoldsTheThreadAfterTheWait() throws InterruptedException {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch never = new CountDownLatch(1);
    final AtomicBoolean interrupted = new AtomicBoolean();
    final DelayedAudioOutput stuck = DelayedAudioOutput.start(
      "the browser",
      DELAY_MILLISECONDS,
      MAXIMUM_QUEUED_MILLISECONDS,
      () -> AudioPipelineStep.of((samples, metadata) -> this.holdTheThreadUntilInterrupted(entered, never, interrupted)),
      (message, failure) -> this.failures.add(message),
      System::nanoTime,
      100,
      DelayedAudioOutput.SILENCE_MILLISECONDS
    );
    stuck.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    final boolean pipelineEntered = entered.await(AWAIT_SECONDS, TimeUnit.SECONDS);
    assertTrue(pipelineEntered, "the pipeline got the samples");

    stuck.close();
    waitUntil(() -> !stuck.getThread().isAlive());
    final boolean pipelineInterrupted = interrupted.get();
    assertTrue(pipelineInterrupted, "the pipeline was asked to let go of the thread");
    assertEquals(List.of(), this.failures);
  }

  private boolean holdTheThreadUntilInterrupted(final CountDownLatch entered, final CountDownLatch never, final AtomicBoolean interrupted) {
    entered.countDown();
    try {
      never.await(30, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      interrupted.set(true);
    }

    return true;
  }

  @Test
  void shouldHoldSamplesUntilTheyAreDue() throws InterruptedException {
    final AtomicLong now = new AtomicLong();
    this.output.close();
    this.output = DelayedAudioOutput.start(
      SOURCE_NAME,
      DELAY_MILLISECONDS,
      MAXIMUM_QUEUED_MILLISECONDS,
      () -> this.step,
      (message, failure) -> this.failures.add(message),
      now::get,
      DelayedAudioOutput.JOIN_TIMEOUT_MILLISECONDS,
      DelayedAudioOutput.SILENCE_MILLISECONDS
    );
    this.output.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    Thread.sleep(3L * DELAY_MILLISECONDS);
    final int processedCount = this.processed.size();
    assertEquals(0, processedCount, "not due before the clock passed the delay");

    now.set(TimeUnit.MILLISECONDS.toNanos(DELAY_MILLISECONDS));
    waitUntil(() -> this.processed.size() == 1);
  }

  private DelayedAudioOutput quietOutput(final AtomicLong now, final int silenceMilliseconds) {
    return this.quietOutput((LongSupplier) now::get, silenceMilliseconds);
  }

  private DelayedAudioOutput quietOutput(final LongSupplier clock, final int silenceMilliseconds) {
    this.output.close();
    this.output = DelayedAudioOutput.start(
      SOURCE_NAME,
      DELAY_MILLISECONDS,
      MAXIMUM_QUEUED_MILLISECONDS,
      () -> this.step,
      (message, failure) -> this.failures.add(message),
      clock,
      DelayedAudioOutput.JOIN_TIMEOUT_MILLISECONDS,
      silenceMilliseconds
    );
    return this.output;
  }

  private static boolean isSilence(final byte[] samples) {
    for (final byte sample : samples) {
      if (sample != 0) {
        return false;
      }
    }

    return true;
  }

  private int silentBytes() {
    int bytes = 0;
    for (final byte[] samples : this.processed) {
      if (isSilence(samples)) {
        bytes += samples.length;
      }
    }

    return bytes;
  }

  private void at(final AtomicLong now, final int milliseconds, final int chunks) {
    now.set(TimeUnit.MILLISECONDS.toNanos(milliseconds));
    waitUntil(() -> this.processed.size() >= chunks);
  }

  @Test
  void shouldFollowAQuietSourceBySilenceInRealTimeUpToItsLimit() {
    final AtomicLong now = new AtomicLong();
    final DelayedAudioOutput quiet = this.quietOutput(now, SILENCE_LIMIT_MILLISECONDS);
    final byte[] sound = new byte[SOUND_CHUNK_BYTES];
    sound[0] = 7;
    quiet.accept(sound, sound.length);
    this.at(now, DELAY_MILLISECONDS, 1);
    final byte firstSample = this.processed.getFirst()[0];
    assertEquals(7, firstSample, "the sound comes first");

    quiet.quiet();
    this.at(now, 80, 2);
    final int silenceLength = this.processed.get(1).length;
    assertEquals(3840, silenceLength, "20 ms of silence");

    final boolean silent = isSilence(this.processed.get(1));
    assertTrue(silent);

    this.at(now, 100, 3);
    this.at(now, 120, 4);
    now.set(TimeUnit.MILLISECONDS.toNanos(LATER_MILLISECONDS));
    quiet.accept(new byte[] { 2, 0, 2, 0 }, AudioFilter.FRAME_SIZE);
    this.at(now, LATER_DUE_MILLISECONDS, 5);
    final byte lastSample = this.processed.getLast()[0];
    assertEquals(2, lastSample);

    final int silentBytes = this.silentBytes();
    assertEquals(3 * 3840, silentBytes, "60 ms of silence at most, then the stream waits for the sound");
  }

  @Test
  void shouldStartTheSilenceOnlyOnceTheSoundHandedOverHasPlayed() {
    final AtomicLong now = new AtomicLong();
    final AtomicInteger reads = new AtomicInteger();
    final DelayedAudioOutput quiet = this.quietOutput(() -> this.countClockRead(reads, now), SILENCE_LIMIT_MILLISECONDS);
    quiet.accept(new byte[SOUND_CHUNK_BYTES], SOUND_CHUNK_BYTES);
    this.at(now, DELAY_MILLISECONDS, 1);
    quiet.quiet();
    final int seen = reads.get();
    waitUntil(() -> reads.get() >= seen + 2);

    final int processedCount = this.processed.size();
    assertEquals(1, processedCount, "the 10 ms of sound still play, so no silence follows yet");
  }

  private long countClockRead(final AtomicInteger reads, final AtomicLong now) {
    reads.incrementAndGet();

    return now.get();
  }

  @Test
  void shouldEndTheSilenceForGoodOnceTheSoundAfterItHasPlayed() {
    final AtomicLong now = new AtomicLong();
    final DelayedAudioOutput quiet = this.quietOutput(now, LONG_SILENCE_LIMIT_MILLISECONDS);
    quiet.quiet();
    quiet.accept(new byte[] { 3, 0, 3, 0 }, AudioFilter.FRAME_SIZE);
    this.at(now, 0, 1);
    this.at(now, DELAY_MILLISECONDS, 2);
    final byte soundSample = this.processed.get(1)[0];
    assertEquals(3, soundSample);

    now.set(TimeUnit.MILLISECONDS.toNanos(LATER_MILLISECONDS));
    waitUntil(() -> quiet.getThread().getState() == Thread.State.WAITING);
    final int silentBytes = this.silentBytes();
    assertEquals(
      SILENCE_CHUNK_MILLISECONDS * BYTES_PER_MILLISECOND,
      silentBytes,
      "only the first chunk of silence, before the sound, which ends the quiet"
    );
  }

  @Test
  void shouldEndTheSilenceWhereTheNextSoundIsDue() {
    final AtomicLong now = new AtomicLong();
    final DelayedAudioOutput quiet = this.quietOutput(now, LONG_SILENCE_LIMIT_MILLISECONDS);
    quiet.quiet();
    this.at(now, 0, 1);
    this.at(now, 20, 2);
    now.set(TimeUnit.MILLISECONDS.toNanos(30));
    quiet.accept(new byte[] { 9, 0, 9, 0 }, AudioFilter.FRAME_SIZE);
    this.at(now, 40, 3);
    this.at(now, 60, 4);
    this.at(now, 80, 5);
    this.at(now, 100, 6);
    final byte lastSample = this.processed.getLast()[0];
    assertEquals(9, lastSample, "the sound, right after the silence");

    final int silentBytes = this.silentBytes();
    assertEquals(100 * BYTES_PER_MILLISECOND, silentBytes, "silence up to the sound, and no more");

    now.set(TimeUnit.MILLISECONDS.toNanos(LATER_MILLISECONDS));
    quiet.accept(new byte[] { 1, 0, 1, 0 }, AudioFilter.FRAME_SIZE);
    this.at(now, LATER_DUE_MILLISECONDS, 7);
    final int silentBytesLater = this.silentBytes();
    assertEquals(100 * BYTES_PER_MILLISECOND, silentBytesLater);
  }

  @Test
  void shouldAddUpASilenceCutBetweenTwoFramesToItsLimitAndThenWait() {
    final long start = TimeUnit.SECONDS.toNanos(1);
    final AtomicLong now = new AtomicLong(start);
    final DelayedAudioOutput quiet = this.quietOutput(now, SILENCE_LIMIT_MILLISECONDS);
    quiet.accept(new byte[] { 5, 0, 5, 0 }, AudioFilter.FRAME_SIZE);
    final long due = start + TimeUnit.MILLISECONDS.toNanos(DELAY_MILLISECONDS);
    now.set(due - FRAME_NANOSECONDS - 1);
    quiet.quiet();
    waitUntil(() -> this.processed.size() == 1);
    final byte[] firstChunk = this.processed.getFirst();
    assertArrayEquals(new byte[AudioFilter.FRAME_SIZE], firstChunk);

    now.set(due);
    waitUntil(() -> this.processed.size() == 2);
    final byte secondChunkStart = this.processed.get(1)[0];
    assertEquals(5, secondChunkStart, "the samples, then the rest of the silence");

    now.set(due + FRAME_NANOSECONDS);
    waitUntil(() -> this.processed.size() == 3);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS));
    waitUntil(() -> this.processed.size() == 4);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS));
    waitUntil(() -> this.processed.size() == 5);
    final int lastChunkLength = this.processed.get(4).length;
    assertEquals(3836, lastChunkLength, "the last chunk is shorter by the rounding of the first");

    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS));
    waitUntil(() -> quiet.getThread().getState() == Thread.State.WAITING);
    final int processedCount = this.processed.size();
    assertEquals(5, processedCount);

    final int silentBytes = this.silentBytes();
    assertEquals(SILENCE_LIMIT_MILLISECONDS * BYTES_PER_MILLISECOND, silentBytes);
  }

  @Test
  void shouldFollowSamplesDueWithinAFrameOfTheQuietMomentByTheSilence() {
    final long start = TimeUnit.SECONDS.toNanos(1);
    final AtomicLong now = new AtomicLong(start);
    final AtomicInteger reads = new AtomicInteger();
    final DelayedAudioOutput quiet = this.quietOutput(() -> this.countClockRead(reads, now), SILENCE_LIMIT_MILLISECONDS);
    quiet.accept(new byte[] { 6, 0, 6, 0 }, AudioFilter.FRAME_SIZE);
    final long due = start + TimeUnit.MILLISECONDS.toNanos(DELAY_MILLISECONDS);
    now.set(due - 10);
    quiet.quiet();
    final int seen = reads.get();
    waitUntil(() -> reads.get() >= seen + 2);
    final boolean nothingProcessed = this.processed.isEmpty();
    assertTrue(nothingProcessed, "nothing comes before the samples");

    now.set(due);
    waitUntil(() -> this.processed.size() == 1);
    final byte firstSample = this.processed.getFirst()[0];
    assertEquals(6, firstSample);

    now.set(due + FRAME_NANOSECONDS);
    waitUntil(() -> this.processed.size() == 2);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS));
    waitUntil(() -> this.processed.size() == 3);
    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SILENCE_CHUNK_MILLISECONDS));
    waitUntil(() -> this.processed.size() == 4);
    final int silentBytes = this.silentBytes();
    assertEquals(SILENCE_LIMIT_MILLISECONDS * BYTES_PER_MILLISECOND, silentBytes);
  }

  @Test
  void shouldFillNoSilenceInAPausedOrClosedOutput() {
    final AtomicLong now = new AtomicLong();
    final DelayedAudioOutput quiet = this.quietOutput(now, LONG_SILENCE_LIMIT_MILLISECONDS);
    quiet.pause();
    quiet.quiet();
    quiet.resume();
    now.set(TimeUnit.MILLISECONDS.toNanos(500));
    quiet.accept(new byte[] { 3, 0, 3, 0 }, AudioFilter.FRAME_SIZE);
    now.set(TimeUnit.MILLISECONDS.toNanos(570));
    waitUntil(() -> this.processed.size() == 1);
    final byte firstSample = this.processed.getFirst()[0];
    assertEquals(3, firstSample, "no silence was asked for while paused");

    quiet.quiet();
    quiet.pause();
    now.set(TimeUnit.MILLISECONDS.toNanos(900));
    quiet.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    final int queuedBytes = quiet.getQueuedBytes();
    assertEquals(0, queuedBytes, "paused");

    final int silentBytes = this.silentBytes();
    assertEquals(0, silentBytes, "pausing ends the silence");

    quiet.close();
    quiet.quiet();
    final int processedCount = this.processed.size();
    assertEquals(1, processedCount);
  }

  @Test
  void shouldDropSamplesInAPausedOutput() {
    this.blocking = true;
    this.output.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> this.output.getQueuedBytes() == 0);
    this.output.accept(new byte[TWO_FRAMES_BYTES], TWO_FRAMES_BYTES);
    this.output.pause();
    final int queuedAfterPause = this.output.getQueuedBytes();
    assertEquals(0, queuedAfterPause, "pausing drops what waits");

    this.output.accept(new byte[TWO_FRAMES_BYTES], TWO_FRAMES_BYTES);
    final int queuedWhilePaused = this.output.getQueuedBytes();
    assertEquals(0, queuedWhilePaused, "and what arrives");

    this.output.resume();
    this.output.accept(new byte[] { 5, 0, 5, 0 }, AudioFilter.FRAME_SIZE);
    this.blocking = false;
    this.gate.countDown();
    waitUntil(() -> this.processed.size() == 2);
    final byte resumedSample = this.processed.get(1)[0];
    assertEquals(5, resumedSample);
  }

  @Test
  void shouldReportAFailingPipelineAndSkipAnEmptyOne() {
    final DelayedAudioOutput empty = start(() -> AudioPipelineStep.NO_OP, (message, failure) -> this.failures.add(message));
    empty.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> empty.getQueuedBytes() == 0);
    empty.close();
    final DelayedAudioOutput failing = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.breakFilter()),
      (message, failure) -> this.failures.add(message + ": " + failure.getMessage())
    );
    failing.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> !this.failures.isEmpty());
    failing.close();
    assertEquals(List.of("Failed to process the audio of the virtual machine: filter broke!"), this.failures);

    final DelayedAudioOutput fatal = start(
      () -> AudioPipelineStep.of((samples, metadata) -> this.failFatally()),
      (message, failure) -> this.failures.add(message)
    );
    fatal.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> !fatal.getThread().isAlive());
    final int failureCount = this.failures.size();
    assertEquals(1, failureCount, "an error of the virtual machine ends the thread instead");
  }

  private boolean breakFilter() {
    throw new IllegalStateException("filter broke!");
  }

  private boolean failFatally() {
    throw new OutOfMemoryError("fatal!");
  }

  @Test
  void shouldEndAClosedOrInterruptedOutput() throws InterruptedException {
    this.output.close();
    final boolean closedAlive = this.output.getThread().isAlive();
    assertFalse(closedAlive);

    this.output.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    final int queuedBytes = this.output.getQueuedBytes();
    assertEquals(0, queuedBytes);

    this.output = start(() -> this.step, (message, failure) -> this.failures.add(message));
    final Thread thread = this.output.getThread();
    thread.interrupt();
    thread.join(JOIN_MILLISECONDS);
    final boolean interruptedAlive = thread.isAlive();
    assertFalse(interruptedAlive);

    final DelayedAudioOutput live = start(() -> this.step, (message, failure) -> this.failures.add(message));
    this.blocking = true;
    live.accept(new byte[AudioFilter.FRAME_SIZE], AudioFilter.FRAME_SIZE);
    waitUntil(() -> live.getQueuedBytes() == 0);
    Thread.currentThread().interrupt();
    try {
      live.close();
      final boolean interrupted = Thread.currentThread().isInterrupted();
      assertTrue(interrupted, "an interrupted close keeps the interrupt");
    } finally {
      Thread.interrupted();
    }

    live.getThread().join(JOIN_MILLISECONDS);
    final boolean liveAlive = live.getThread().isAlive();
    assertFalse(liveAlive);
  }

  @Test
  void shouldTrimBeforeTheQueuedByteSumCanOverflow() throws ReflectiveOperationException {
    this.output.close();
    this.output = DelayedAudioOutput.start(
      "queue probe",
      1,
      DelayedAudioOutput.MAXIMUM_QUEUED_MILLISECONDS,
      () -> this.step,
      (message, failure) -> this.failures.add(message),
      () -> 0L,
      DelayedAudioOutput.JOIN_TIMEOUT_MILLISECONDS,
      DelayedAudioOutput.SILENCE_MILLISECONDS
    );
    final int capacity = DelayedAudioOutput.MAXIMUM_QUEUED_MILLISECONDS * BYTES_PER_MILLISECOND;
    final ByteBuffer samples = Mockito.mock(ByteBuffer.allocate(1).getClass());
    Mockito.when(samples.remaining()).thenReturn(capacity);
    final Class<?> chunkType = Class.forName(DelayedAudioOutput.class.getName() + "$Chunk");
    final Constructor<?> constructor = chunkType.getDeclaredConstructor(ByteBuffer.class, long.class, boolean.class);
    constructor.setAccessible(true);
    final Object chunk = constructor.newInstance(samples, TimeUnit.DAYS.toNanos(1), false);
    final Field queueField = DelayedAudioOutput.class.getDeclaredField("queue");
    queueField.setAccessible(true);
    final Field counterField = DelayedAudioOutput.class.getDeclaredField("queuedBytes");
    counterField.setAccessible(true);
    final DelayedAudioOutput running = this.output;
    synchronized (running) {
      final Object queue = queueField.get(this.output);
      Deque.class.getMethod("addLast", Object.class).invoke(queue, chunk);
      counterField.setInt(this.output, capacity);
      this.output.accept(new byte[BYTES_PER_MILLISECOND], BYTES_PER_MILLISECOND);
      final int queuedBytes = this.output.getQueuedBytes();
      assertEquals(BYTES_PER_MILLISECOND, queuedBytes, "the oldest chunk must be evicted before the counter wraps");

      final int queueSize = ((Deque<?>) queue).size();
      assertEquals(1, queueSize);

      final boolean containsChunk = ((Deque<?>) queue).contains(chunk);
      assertFalse(containsChunk);
    }
  }

  @Test
  void shouldRefuseAQueueTooLongForItsSizeInBytes() {
    final Supplier<AudioPipelineStep> pipeline = () -> this.step;
    final BiConsumer<String, Throwable> ignored = (message, failure) -> {};
    final IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () ->
      DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 0, 11_184_811, pipeline, ignored)
    );
    final String tooLongMessage = tooLong.getMessage();
    assertEquals("At most 11184810 ms of sound may wait, but 11184811 ms were asked for!", tooLongMessage);

    final DelayedAudioOutput longest = DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 0, 11_184_810, pipeline, ignored);
    try {
      longest.accept(new byte[] { 7, 0, 7, 0 }, AudioFilter.FRAME_SIZE);
      waitUntil(() -> this.processed.size() == 1);
    } finally {
      longest.close();
    }
  }

  @Test
  void shouldNeedADelayAndRoomPastItForAnOutput() {
    final Supplier<AudioPipelineStep> pipeline = () -> this.step;
    final BiConsumer<String, Throwable> ignored = (message, failure) -> {};
    final IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () ->
      DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, -1, 10, pipeline, ignored)
    );
    final String negativeMessage = negative.getMessage();
    assertEquals("Delay -1 must not be negative!", negativeMessage);

    final IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () ->
      DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 10, 10, pipeline, ignored)
    );
    final String fullMessage = full.getMessage();
    assertEquals("At most 10 ms may wait, which leaves no room past the delay of 10 ms!", fullMessage);
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start(null, 0, 1, pipeline, ignored));
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 0, 1, null, ignored));
    assertThrows(NullPointerException.class, () -> DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 0, 1, pipeline, null));

    final DelayedAudioOutput immediate = DelayedAudioOutput.start(VALIDATED_SOURCE_NAME, 0, 1, pipeline, ignored);
    try {
      immediate.accept(new byte[] { 7, 0, 7, 0 }, AudioFilter.FRAME_SIZE);
      waitUntil(() -> this.processed.size() == 1);
      final byte firstSample = this.processed.getFirst()[0];
      assertEquals(7, firstSample, "no delay hands the samples over at once");

      immediate.accept(new byte[400], 400);
      final boolean withinOneMillisecond = immediate.getQueuedBytes() <= BYTES_PER_MILLISECOND;
      assertTrue(withinOneMillisecond, "1 ms holds 48 frames of 4 bytes");
    } finally {
      immediate.close();
    }
  }

  private static DelayedAudioOutput start(final Supplier<AudioPipelineStep> pipeline, final BiConsumer<String, Throwable> failures) {
    return DelayedAudioOutput.start(SOURCE_NAME, DELAY_MILLISECONDS, MAXIMUM_QUEUED_MILLISECONDS, pipeline, failures);
  }

  private enum ReporterError {
    ASSERTION,
    LINKAGE,
  }
}
