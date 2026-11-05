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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.jna.Memory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import org.bytedeco.ffmpeg.global.avutil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

final class AudioRendererTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final long QUIET_PERIOD_MILLISECONDS = 200L;

  private static final int SURVIVING_CHUNK_COUNT = 2;

  private final MockVlc vlc = new MockVlc();
  private final VlcPlayer owner = this.vlc.newPlayer(1_000L);
  private final AudioRenderer renderer = new AudioRenderer(this.owner);
  private final MediaPlayer mediaPlayer = mock(MediaPlayer.class);
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  AudioRendererTest() {
    this.owner.setExceptionHandler(this::recordFailure);
  }

  private void recordFailure(final String message, final Throwable failure) {
    this.messages.add(message);
    this.errors.add(failure);
  }

  @AfterEach
  void stopRenderer() {
    this.renderer.stop();
  }

  private void attach(final AudioFilter filter) {
    final AudioPipelineStep step = AudioPipelineStep.of(filter);
    final AudioAttachableCallback callback = this.owner.getAudioAttachableCallback();
    callback.attach(step);
  }

  private static AudioFilter firstSampleRecorder(final BlockingQueue<Short> firstSamples) {
    return (samples, _) -> recordFirstSample(samples, firstSamples);
  }

  private static boolean recordFirstSample(final ByteBuffer samples, final BlockingQueue<Short> firstSamples) {
    final short first = samples.getShort(0);
    firstSamples.add(first);

    return false;
  }

  // Attaches a filter that records the first sample of every chunk, then blocks the render thread until the test lets
  // it proceed, so later chunks queue up.
  private void attachBlockingRecorder(final List<Short> received, final CountDownLatch blocked, final CountDownLatch proceed) {
    this.attach((samples, _) -> this.recordFirstSampleAndBlock(samples, received, blocked, proceed));
  }

  private boolean recordFirstSampleAndBlock(
    final ByteBuffer samples,
    final List<Short> received,
    final CountDownLatch blocked,
    final CountDownLatch proceed
  ) {
    final short first = samples.getShort(0);
    received.add(first);
    blocked.countDown();
    awaitRelease(proceed);

    return false;
  }

  private static AudioFilter copyingRecorder(
    final AtomicReference<ByteBuffer> received,
    final AtomicReference<OriginalAudioMetadata> receivedMetadata,
    final CountDownLatch done
  ) {
    return (samples, metadata) -> copyChunk(samples, metadata, received, receivedMetadata, done);
  }

  private static boolean copyChunk(
    final ByteBuffer samples,
    final OriginalAudioMetadata metadata,
    final AtomicReference<ByteBuffer> received,
    final AtomicReference<OriginalAudioMetadata> receivedMetadata,
    final CountDownLatch done
  ) {
    final int remaining = samples.remaining();
    final ByteOrder order = samples.order();
    final ByteBuffer copy = ByteBuffer.allocate(remaining);
    copy.order(order);
    copy.put(samples);
    copy.flip();
    received.set(copy);
    receivedMetadata.set(metadata);
    done.countDown();

    return false;
  }

  private static BiConsumer<String, Throwable> reportingHandler(final List<String> reported) {
    return (message, failure) -> reportFailure(reported, message, failure);
  }

  private static void reportFailure(final List<String> reported, final String message, final Throwable failure) {
    final String reason = failure.getMessage();
    reported.add("%s: %s".formatted(message, reason));
  }

  private static Memory samples(final short... values) {
    final Memory memory = new Memory((long) values.length * Short.BYTES);
    memory.write(0, values, 0, values.length);
    return memory;
  }

  private void play(final short... interleaved) {
    final Memory memory = samples(interleaved);
    this.renderer.play(this.mediaPlayer, memory, interleaved.length / AudioFilter.CHANNELS, 0L);
  }

  private static void assertPcmMetadata(final OriginalAudioMetadata metadata) {
    final int sampleRate = metadata.getAudioSampleRate();
    final int channels = metadata.getAudioChannels();
    final int bitrate = metadata.getAudioBitrate();
    final String codec = metadata.getAudioCodec();
    final int samplingFormat = metadata.getSamplingFormat();
    assertEquals(48_000, sampleRate);
    assertEquals(2, channels);
    final int expectedBitrate = 48_000 * 2 * 16;
    assertEquals(expectedBitrate, bitrate, "the bitrate of 16-bit stereo PCM at 48 kHz");
    assertEquals("pcm_s16le", codec);
    assertEquals(avutil.AV_SAMPLE_FMT_S16, samplingFormat);
  }

  @Test
  void shouldCopyTheSamplesVlcDecodedIntoThePipeline() throws Exception {
    final AtomicReference<ByteBuffer> received = new AtomicReference<>();
    final AtomicReference<OriginalAudioMetadata> receivedMetadata = new AtomicReference<>();
    final CountDownLatch done = new CountDownLatch(1);
    final AudioFilter recorder = copyingRecorder(received, receivedMetadata, done);
    this.attach(recorder);
    this.renderer.start();
    final short[] interleaved = { 1, -1, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE };
    final Memory memory = samples(interleaved);
    this.renderer.play(this.mediaPlayer, memory, 3, 1234L);
    memory.clear();
    final boolean delivered = done.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(delivered);

    final ByteBuffer buffer = received.get();
    final int remaining = buffer.remaining();
    final ByteOrder order = buffer.order();
    final ShortBuffer shorts = buffer.asShortBuffer();
    final int sampleCount = shorts.remaining();
    final short[] copied = new short[sampleCount];
    shorts.get(copied);
    final OriginalAudioMetadata metadata = receivedMetadata.get();
    final int expected = 3 * AudioFilter.FRAME_SIZE;
    assertEquals(expected, remaining, "three stereo frames of 16-bit samples");
    assertEquals(ByteOrder.LITTLE_ENDIAN, order);
    assertArrayEquals(interleaved, copied, "the samples are copied before VLC reuses its memory");
    assertPcmMetadata(metadata);
  }

  @Test
  void shouldDropSamplesBeforeItIsStartedAndAfterItIsStopped() throws Exception {
    final BlockingQueue<Short> firstSamples = new LinkedBlockingQueue<>();
    final AudioFilter recorder = firstSampleRecorder(firstSamples);
    this.attach(recorder);
    this.play((short) 1, (short) 1);
    this.renderer.start();
    this.play((short) 2, (short) 2);
    final Short second = firstSamples.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    this.renderer.stop();
    this.play((short) 3, (short) 3);
    final Short third = firstSamples.poll(QUIET_PERIOD_MILLISECONDS, TimeUnit.MILLISECONDS);
    final boolean nothingElse = firstSamples.isEmpty();
    final Short expectedSecond = 2;
    assertEquals(expectedSecond, second);
    assertNull(third);
    assertTrue(nothingElse);
  }

  @Test
  void shouldDropSamplesWithoutAPipelineAndPlayTheNextChunkOnceOneIsAttached() throws Exception {
    final BlockingQueue<Short> firstSamples = new LinkedBlockingQueue<>();
    final AudioFilter recorder = firstSampleRecorder(firstSamples);
    final AudioPipelineStep recording = AudioPipelineStep.of(recorder);
    final AudioAttachableCallback callback = mock(AudioAttachableCallback.class);
    when(callback.retrieve()).thenReturn(AudioPipelineStep.NO_OPERATION, recording);
    final VideoPlayerMultiplexer detachedOwner = mock(VideoPlayerMultiplexer.class);
    when(detachedOwner.getAudioAttachableCallback()).thenReturn(callback);
    final AudioRenderer detached = new AudioRenderer(detachedOwner);
    detached.start();
    try {
      final Memory memory = samples((short) 1, (short) 1);
      detached.play(this.mediaPlayer, memory, 1, 0L);
      final Short received = firstSamples.poll(QUIET_PERIOD_MILLISECONDS, TimeUnit.MILLISECONDS);
      assertNull(received, "samples played without a pipeline are never queued");
      verify(callback, times(1)).retrieve();

      final Memory nextMemory = samples((short) 2, (short) 2);
      detached.play(this.mediaPlayer, nextMemory, 1, 0L);
      final Short next = firstSamples.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      final Short expected = 2;
      assertEquals(expected, next, "the chunk after the pipeline is attached reaches it");
    } finally {
      detached.stop();
    }
  }

  @Test
  void shouldDropTheOldestChunksWhenThePipelineFallsBehind() throws Exception {
    final CountDownLatch blocked = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Short> received = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(received, blocked, proceed);
    this.renderer.start();
    this.play((short) 0, (short) 0);
    final boolean blocking = blocked.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(blocking);

    for (short chunk = 1; chunk <= 70; chunk++) {
      this.play(chunk, chunk);
    }

    proceed.countDown();
    awaitSize(received, 65);

    final List<Short> expected = new ArrayList<>();
    expected.add((short) 0);
    for (short chunk = 7; chunk <= 70; chunk++) {
      expected.add(chunk);
    }

    assertEquals(expected, received, "the 64 newest chunks are kept");
  }

  @Test
  void shouldDropTheQueuedChunksWhenFlushing() throws Exception {
    final CountDownLatch blocked = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Short> received = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(received, blocked, proceed);
    this.renderer.start();
    this.play((short) 0, (short) 0);
    final boolean blocking = blocked.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(blocking);

    this.play((short) 1, (short) 1);
    this.play((short) 2, (short) 2);
    this.renderer.flush(this.mediaPlayer, 0L);
    this.play((short) 3, (short) 3);
    proceed.countDown();
    awaitSize(received, SURVIVING_CHUNK_COUNT);
    final List<Short> expected = List.of((short) 0, (short) 3);
    assertEquals(expected, received);
  }

  @Test
  void shouldLetFatalFilterFailuresEscapeWithoutBeingReported() throws Exception {
    final OutOfMemoryError failure = new OutOfMemoryError("filter exhausted memory!");
    final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    final CountDownLatch escaped = new CountDownLatch(1);
    this.attach((_, _) -> this.escapeWithFailure(failure, uncaught, escaped));

    this.renderer.start();
    this.play((short) 1, (short) 1);
    final boolean ended = escaped.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    final Throwable observed = uncaught.get();
    final boolean reportedNothing = this.errors.isEmpty();
    assertTrue(ended);
    assertSame(failure, observed);
    assertTrue(reportedNothing, "fatal VM failures must not be converted to ordinary filter reports");
  }

  private boolean escapeWithFailure(
    final OutOfMemoryError failure,
    final AtomicReference<Throwable> uncaught,
    final CountDownLatch escaped
  ) {
    final Thread worker = Thread.currentThread();
    worker.setUncaughtExceptionHandler((_, thrown) -> this.recordEscape(thrown, uncaught, escaped));
    throw failure;
  }

  private void recordEscape(final Throwable thrown, final AtomicReference<Throwable> uncaught, final CountDownLatch escaped) {
    uncaught.set(thrown);
    escaped.countDown();
  }

  @Test
  void shouldReportPipelineFailuresAndKeepPlaying() throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    final IllegalStateException runtimeFailure = new IllegalStateException("filter bug!");
    final AssertionError errorFailure = new AssertionError("filter assertion!");
    final CountDownLatch third = new CountDownLatch(1);
    this.attach((_, _) -> this.failTwiceThenCount(calls, runtimeFailure, errorFailure, third));
    this.renderer.start();
    this.play((short) 1, (short) 1);
    this.play((short) 2, (short) 2);
    this.play((short) 3, (short) 3);
    final boolean keptPlaying = third.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    final List<String> expectedMessages = List.of("Audio filter failed.", "Audio filter failed.");
    final List<Throwable> expectedErrors = List.of(runtimeFailure, errorFailure);
    assertTrue(keptPlaying);
    assertEquals(expectedMessages, this.messages);
    assertEquals(expectedErrors, this.errors);
  }

  private boolean failTwiceThenCount(
    final AtomicInteger calls,
    final IllegalStateException runtimeFailure,
    final AssertionError errorFailure,
    final CountDownLatch third
  ) {
    final int call = calls.incrementAndGet();
    if (call == 1) {
      throw runtimeFailure;
    }

    if (call == 2) {
      throw errorFailure;
    }

    third.countDown();

    return false;
  }

  @Test
  void shouldDropTheQueuedChunksWhenPausing() throws Exception {
    final CountDownLatch blocked = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final List<Short> received = Collections.synchronizedList(new ArrayList<>());
    this.attachBlockingRecorder(received, blocked, proceed);
    this.renderer.start();
    this.play((short) 0, (short) 0);
    final boolean blocking = blocked.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(blocking);

    this.play((short) 1, (short) 1);
    this.play((short) 2, (short) 2);
    this.renderer.pause(this.mediaPlayer, 0L);
    this.play((short) 3, (short) 3);
    proceed.countDown();
    awaitSize(received, SURVIVING_CHUNK_COUNT);
    final List<Short> expected = List.of((short) 0, (short) 3);
    assertEquals(expected, received, "audio queued before the pause does not play after it");
  }

  @Test
  void shouldCopySamplesOfEitherByteOrderIntoLittleEndianOrder() {
    final ByteBuffer bigEndian = ByteBuffer.wrap(new byte[] { 0x01, 0x02, (byte) 0xFF, (byte) 0xFE });
    final ByteBuffer littleEndian = ByteBuffer.wrap(new byte[] { 0x02, 0x01, (byte) 0xFE, (byte) 0xFF });
    final ByteBuffer fromBigEndian = this.renderer.copySamples(bigEndian, ByteOrder.BIG_ENDIAN);
    final ByteBuffer fromLittleEndian = this.renderer.copySamples(littleEndian, ByteOrder.LITTLE_ENDIAN);
    final byte[] swapped = new byte[4];
    fromBigEndian.get(swapped);
    final byte[] kept = new byte[4];
    fromLittleEndian.get(kept);
    final ByteOrder order = fromBigEndian.order();
    final int bigEndianPosition = bigEndian.position();
    final byte[] expected = new byte[] { 0x02, 0x01, (byte) 0xFE, (byte) 0xFF };
    assertArrayEquals(expected, swapped, "big-endian machines swap every sample");
    assertArrayEquals(expected, kept, "little-endian machines copy the samples");
    assertEquals(ByteOrder.LITTLE_ENDIAN, order);
    assertEquals(0, bigEndianPosition, "the native samples are not consumed");
  }

  private static VideoPlayerMultiplexer ownerFailingOnce(
    final AudioAttachableCallback callback,
    final RuntimeException failure,
    final List<String> reported
  ) {
    final VideoPlayerMultiplexer failingOwner = mock(VideoPlayerMultiplexer.class);
    final BiConsumer<String, Throwable> handler = reportingHandler(reported);
    when(failingOwner.getExceptionHandler()).thenReturn(handler);
    when(failingOwner.getAudioAttachableCallback()).thenReturn(callback).thenThrow(failure).thenReturn(callback);
    return failingOwner;
  }

  @Test
  void shouldReportRenderingFailuresAndKeepRendering() throws Exception {
    final List<String> reported = Collections.synchronizedList(new ArrayList<>());
    final BlockingQueue<Short> firstSamples = new LinkedBlockingQueue<>();
    final AudioFilter recorder = firstSampleRecorder(firstSamples);
    final AudioPipelineStep recording = AudioPipelineStep.of(recorder);
    final AudioAttachableCallback callback = AudioAttachableCallback.create();
    callback.attach(recording);
    final IllegalStateException failure = new IllegalStateException("native failure!");
    final VideoPlayerMultiplexer failingOwner = ownerFailingOnce(callback, failure, reported);
    final AudioRenderer failing = new AudioRenderer(failingOwner);
    failing.start();
    try {
      final Memory first = samples((short) 1, (short) 1);
      failing.play(this.mediaPlayer, first, 1, 0L);
      Polling.awaitCondition("the rendering failure was reported", TIMEOUT, () -> !reported.isEmpty());
      final Memory second = samples((short) 2, (short) 2);
      failing.play(this.mediaPlayer, second, 1, 0L);
      final Short received = firstSamples.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      final List<String> expectedReports = List.of("Audio rendering failed.: native failure!");
      final Short expectedSample = 2;
      assertEquals(expectedReports, reported);
      assertEquals(expectedSample, received, "the render thread survived the failure");
    } finally {
      failing.stop();
    }
  }

  private static void awaitSize(final List<?> list, final int size) throws InterruptedException {
    final boolean reached = Polling.pollUntil(TIMEOUT, () -> list.size() >= size);
    final int actual = list.size();
    assertTrue(reached, "expected %s chunks but got %s".formatted(size, actual));
  }

  // Blocks a filter until the test lets it proceed. A filter that is never released fails, which the render thread
  // reports to the exception handler.
  private static void awaitRelease(final CountDownLatch proceed) {
    try {
      final boolean released = proceed.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      assertTrue(released, "the test released the blocked filter");
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }
}
