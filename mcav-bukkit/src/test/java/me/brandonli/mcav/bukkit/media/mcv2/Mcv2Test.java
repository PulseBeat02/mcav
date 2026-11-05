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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2Test {

  private static final int ROOT_SIZE = 32;

  private static final int SINGLE_BLOCK_KEYFRAME_BYTES = 48;

  private static final int PARALLEL_FRAME_WIDTH = 128;
  private static final int PARALLEL_FRAME_HEIGHT = 96;

  private static final int LAMBDA = 72;

  @Test
  void shouldWriteTheHandComputedUniformKeyframeAndPredictedGolden() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
      final byte[] picture = { 10, 20, 30 };
      final byte[] expected = HexFormat.of().parseHex(
        "4d43563203000000010001000100000001000000010000000000000001000000000000000000000002000000000a141e"
      );
      final byte[] encodedKeyframe = encoder.encode(picture, 1, 1, 1);
      assertArrayEquals(expected, encodedKeyframe);

      final byte[] predicted = HexFormat.of().parseHex("4d435632030000000100010002000000010000000000000000000000000000000000000000000000");
      final byte[] encodedPredicted = encoder.encode(picture, 1, 1, 2);
      assertArrayEquals(predicted, encodedPredicted);

      final byte[] decoded = Mcv2Decoder.decode(predicted, picture, 1);
      assertArrayEquals(picture, decoded);

      final int bytes = encoder.getStatistics().orElseThrow().getBytes();
      assertEquals(40, bytes);

      final boolean keyframe = encoder.getStatistics().orElseThrow().isKeyframe();
      assertFalse(keyframe);

      final double lambda = encoder.getStatistics().orElseThrow().getLambda();
      assertEquals(LAMBDA, lambda);
    }
  }

  @Test
  void shouldReconstructExactlyWhatDecodeGivesForEveryPresetAndOddSize() {
    final Random random = new Random(0x4D435632);
    for (final Mcv2.Settings settings : new Mcv2.Settings[] { Mcv2.Settings.DEFAULT, Mcv2.Settings.FAST }) {
      this.assertReconstructsEveryFrame(settings, random);
    }
  }

  private void assertReconstructsEveryFrame(final Mcv2.Settings settings, final Random random) {
    try (final ForkJoinPool pool = new ForkJoinPool(4)) {
      final Mcv2 encoder = new Mcv2(settings, pool, 4, Mcv2.Verification.VERIFIED);
      byte[] reference = null;
      for (int index = 0; index < 12; index++) {
        reference = this.reconstructCheckedFrame(encoder, random, index, reference);
      }
    }
  }

  private byte[] reconstructCheckedFrame(final Mcv2 encoder, final Random random, final int index, final byte @Nullable [] previous) {
    final byte[] picture = new byte[65 * 37 * 3];
    random.nextBytes(picture);
    final byte[] data = encoder.encode(picture, 65, 37, index);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    final byte[] reference = Mcv2Decoder.decode(frame, previous, frame.getReferenceId());
    final byte[] encoderReference = encoder.getReference().orElseThrow();
    assertArrayEquals(reference, encoderReference);

    final long frameId = frame.getFrameId();
    assertEquals(index, frameId);

    final boolean withinLimit = data.length <= Mcv2Decoder.MAXIMUM_FRAME_BYTES;
    assertTrue(withinLimit);
    if (frame.isKeyframe()) {
      return reference;
    }

    final int expectedReferenceId = index - 1;
    final long referenceId = frame.getReferenceId();
    assertEquals(expectedReferenceId, referenceId);

    return reference;
  }

  @Test
  void shouldWriteIdenticalBytesOnOneThroughFourThreads() {
    final byte[][] source = new byte[5][PARALLEL_FRAME_WIDTH * PARALLEL_FRAME_HEIGHT * 3];
    final Random random = new Random(7719);
    random.nextBytes(source[0]);
    for (int index = 1; index < source.length; index++) {
      System.arraycopy(source[index - 1], 0, source[index], 0, source[index].length);
      for (int channel = 0; channel < source[index].length; channel += 19) {
        source[index][channel]++;
      }
    }

    for (final Mcv2.Settings settings : new Mcv2.Settings[] { Mcv2.Settings.DEFAULT, Mcv2.Settings.FAST }) {
      final byte[][] golden = this.encodeOnThreads(settings, source, 1);
      for (int threads = 2; threads <= 4; threads++) {
        final byte[][] actual = this.encodeOnThreads(settings, source, threads);
        assertArrayEquals(golden, actual);
      }
    }
  }

  private byte[][] encodeOnThreads(final Mcv2.Settings settings, final byte[][] source, final int threads) {
    final byte[][] encoded = new byte[source.length][];
    try (final ForkJoinPool pool = new ForkJoinPool(threads)) {
      final Mcv2 encoder = new Mcv2(settings, pool, threads, Mcv2.Verification.VERIFIED);
      for (int index = 0; index < source.length; index++) {
        encoded[index] = encoder.encode(source[index], PARALLEL_FRAME_WIDTH, PARALLEL_FRAME_HEIGHT, index);
      }
    }

    return encoded;
  }

  @Test
  void shouldRetryAndFallBackFromTheSameReference() {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(0), pool, 2, Mcv2.Verification.VERIFIED);
      final byte[] source = new byte[63 * 65 * 3];
      new Random(17).nextBytes(source);
      encoder.setFrameLimit(56);
      final byte[] first = encoder.encode(source, 63, 65, 0);
      final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(first);
      for (int index = 0; index < parsed.getLeafCount(); index++) {
        final int leafSize = parsed.getLeaf(index).size();
        assertEquals(ROOT_SIZE, leafSize);
      }

      final boolean withinLimit = first.length <= Mcv2Decoder.MAXIMUM_FRAME_BYTES;
      assertTrue(withinLimit);

      final byte[] reference = encoder.getReference().orElseThrow();
      source[0]++;
      final byte[] second = encoder.encode(source, 63, 65, 1);
      final byte[] referenceAfterFallback = encoder.getReference().orElseThrow();
      assertArrayEquals(reference, referenceAfterFallback);

      final long referenceId = Mcv2Decoder.parse(second).getReferenceId();
      assertEquals(0, referenceId);

      final byte[] decoded = Mcv2Decoder.decode(second, reference, 0);
      assertArrayEquals(reference, decoded);
    }
  }

  @Test
  void shouldRetryAtMostFourTimesWhenEvenTheTrivialFrameExceedsTheLimit() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(1), pool, 1, Mcv2.Verification.VERIFIED);
      encoder.setFrameLimit(40);
      final byte[] source = { 10, 20, 30 };
      final byte[] data = encoder.encode(source, 1, 1, 0);
      final double lambda = encoder.getStatistics().orElseThrow().getLambda();
      assertEquals(16, lambda);

      final int frameLength = data.length;
      assertEquals(SINGLE_BLOCK_KEYFRAME_BYTES, frameLength);

      final byte[] decoded = Mcv2Decoder.decode(data, null, 0);
      assertArrayEquals(source, decoded);
    }
  }

  @Test
  void shouldMeasureOnlyTheTimeSpentBeginningAndFinishing() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
      final long started = System.nanoTime();
      final Mcv2.Pending pending = encoder.begin(new byte[] { 10, 20, 30 }, 1, 1, 0);
      final long searched = (long) Mcv2Internals.field(Mcv2.Pending.class, pending, "nanoseconds");
      final boolean searchWithinElapsed = searched >= 0 && searched <= System.nanoTime() - started;
      assertTrue(searchWithinElapsed);

      final Mcv2.Statistics statistics = encoder.finish(pending).getStatistics();
      final boolean measuredWithinElapsed =
        statistics.getNanoseconds() >= searched && statistics.getNanoseconds() <= System.nanoTime() - started;
      assertTrue(measuredWithinElapsed);
    }
  }

  @Test
  void shouldUseMeanSolidsAndThenSkipWhenTheBudgetIsExhausted() {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 2, Mcv2.Verification.VERIFIED);
      encoder.setFrameBudget(1);
      final byte[] source = new byte[ROOT_SIZE * ROOT_SIZE * 3];
      for (int pixel = 0; pixel < ROOT_SIZE * ROOT_SIZE; pixel++) {
        source[pixel * 3] = (byte) ((pixel % ROOT_SIZE) * 2);
      }

      final byte[] first = encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 0);
      final byte[] decoded = Mcv2Decoder.decode(first, null, 0);
      for (int pixel = 0; pixel < ROOT_SIZE * ROOT_SIZE; pixel++) {
        final byte red = decoded[pixel * 3];
        assertEquals(31, red);
      }

      final int firstLength = first.length;
      assertEquals(SINGLE_BLOCK_KEYFRAME_BYTES, firstLength);

      source[0] = 10;
      final int secondLength = encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 1).length;
      assertEquals(40, secondLength);

      final byte[] reference = encoder.getReference().orElseThrow();
      assertArrayEquals(decoded, reference);
    }
  }

  @Test
  void shouldPlaceKeyframesByRequestsSizesIntervalsAndWrappedIds() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.of(LAMBDA, false), pool, 1, Mcv2.Verification.VERIFIED);
      final byte[] black = new byte[3];
      final boolean firstIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0xFFFFFFFEL)).isKeyframe();
      assertTrue(firstIsKeyframe);

      final boolean maximumIdIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0xFFFFFFFFL)).isKeyframe();
      assertFalse(maximumIdIsKeyframe);

      final boolean wrappedIdIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0)).isKeyframe();
      assertFalse(wrappedIdIsKeyframe);

      for (int id = 1; id <= 117; id++) {
        final boolean idIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, id)).isKeyframe();
        assertFalse(idIsKeyframe);
      }

      final boolean intervalIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, 118)).isKeyframe();
      assertTrue(intervalIsKeyframe);

      encoder.requestKeyframe();
      final boolean requestedIsKeyframe = Mcv2Decoder.parse(encoder.encode(black, 1, 1, 119)).isKeyframe();
      assertTrue(requestedIsKeyframe);

      final boolean resizedIsKeyframe = Mcv2Decoder.parse(encoder.encode(new byte[6], 2, 1, 120)).isKeyframe();
      assertTrue(resizedIsKeyframe);

      final byte[] white = new byte[6];
      Arrays.fill(white, (byte) 255);
      final boolean changedIsKeyframe = Mcv2Decoder.parse(encoder.encode(white, 2, 1, 121)).isKeyframe();
      assertFalse(changedIsKeyframe);
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(white, 2, 1, 121));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(white, 2, 1, 0x80000079L));
    }
  }

  @Test
  void shouldKeepTheReferenceAndMotionLambdaWhenSwitchingSettings() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
      final byte[] source = new byte[ROOT_SIZE * ROOT_SIZE * 3];
      encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 0);
      Arrays.fill(source, (byte) 10);
      encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 1);
      Arrays.fill(source, (byte) 20);
      encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 2);
      final double raised = LAMBDA * Math.pow(10 / 4.6, 0.79);
      final double lambda = encoder.getStatistics().orElseThrow().getLambda();
      assertEquals(raised, lambda);

      encoder.setSettings(Mcv2.Settings.FAST.withLambda(30));
      final Mcv2.Settings expectedSettings = Mcv2.Settings.FAST.withLambda(30);
      final Mcv2.Settings settings = encoder.getSettings();
      assertEquals(expectedSettings, settings);

      final Mcv2Decoder.Frame next = Mcv2Decoder.parse(encoder.encode(source, ROOT_SIZE, ROOT_SIZE, 3));
      final boolean keyframe = next.isKeyframe();
      assertFalse(keyframe);

      final long referenceId = next.getReferenceId();
      assertEquals(2, referenceId);

      final double expectedLambda = 30 * Math.pow(10 / 4.6, 0.79);
      final double lambdaAfterSwitch = encoder.getStatistics().orElseThrow().getLambda();
      assertEquals(expectedLambda, lambdaAfterSwitch);
    }
  }

  @Test
  void shouldMatchSerialInThePipelineAndEnforceFinishOrderAndOwnership() {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final Mcv2 serial = new Mcv2(Mcv2.Settings.DEFAULT, pool, 2, Mcv2.Verification.VERIFIED);
      final Mcv2 pipelined = new Mcv2(Mcv2.Settings.DEFAULT, pool, 2, Mcv2.Verification.VERIFIED);
      final byte[][] source = new byte[3][ROOT_SIZE * ROOT_SIZE * 3];
      final Random random = new Random(171);
      for (final byte[] picture : source) {
        random.nextBytes(picture);
      }

      final Mcv2.Pending first = pipelined.begin(source[0], ROOT_SIZE, ROOT_SIZE, 0);
      final Mcv2.Pending second = pipelined.begin(source[1], ROOT_SIZE, ROOT_SIZE, 1);
      assertThrows(IllegalStateException.class, () -> pipelined.begin(source[2], ROOT_SIZE, ROOT_SIZE, 2));
      assertThrows(IllegalStateException.class, () -> pipelined.finish(second));
      assertThrows(IllegalArgumentException.class, () -> serial.finish(first));

      final byte[] expectedFirst = serial.encode(source[0], ROOT_SIZE, ROOT_SIZE, 0);
      final byte[] pipelinedFirst = pipelined.finish(first).getData();
      assertArrayEquals(expectedFirst, pipelinedFirst);

      final Mcv2.Pending third = pipelined.begin(source[2], ROOT_SIZE, ROOT_SIZE, 2);
      final byte[] expectedSecond = serial.encode(source[1], ROOT_SIZE, ROOT_SIZE, 1);
      final byte[] pipelinedSecond = pipelined.finish(second).getData();
      assertArrayEquals(expectedSecond, pipelinedSecond);

      final byte[] expectedThird = serial.encode(source[2], ROOT_SIZE, ROOT_SIZE, 2);
      final byte[] pipelinedThird = pipelined.finish(third).getData();
      assertArrayEquals(expectedThird, pipelinedThird);
      assertThrows(IllegalStateException.class, () -> pipelined.finish(third));
    }
  }

  @Test
  void shouldPermanentlyStopBothPipelineStagesOnAVerificationFailure() throws ReflectiveOperationException {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
      final Mcv2.Pending first = encoder.begin(new byte[3], 1, 1, 0);
      final Mcv2.Pending second = encoder.begin(new byte[3], 1, 1, 1);
      final Field picture = Mcv2.Pending.class.getDeclaredField("picture");
      picture.setAccessible(true);
      ((byte[]) picture.get(first))[0] = 1;
      assertThrows(IllegalStateException.class, () -> encoder.finish(first));
      assertThrows(IllegalStateException.class, () -> encoder.finish(second));
      assertThrows(IllegalStateException.class, () -> encoder.begin(new byte[3], 1, 1, 2));

      final Optional<Mcv2.Statistics> statistics = encoder.getStatistics();
      final boolean statisticsAbsent = statistics.isEmpty();
      assertTrue(statisticsAbsent);
    }
  }

  @Test
  void shouldCheckSettingsInputsAndReferenceOwnership() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      assertThrows(IllegalArgumentException.class, () -> Mcv2.Settings.of(-1, false));
      assertThrows(IllegalArgumentException.class, () -> Mcv2.Settings.of(Double.NaN, false));
      assertThrows(IllegalArgumentException.class, () -> Mcv2.Settings.of(Double.POSITIVE_INFINITY, false));
      assertThrows(IllegalArgumentException.class, () -> new Mcv2(Mcv2.Settings.DEFAULT, pool, 0, Mcv2.Verification.UNVERIFIED));
      assertThrows(NullPointerException.class, () -> new Mcv2(null, pool, 1, Mcv2.Verification.UNVERIFIED));
      assertThrows(NullPointerException.class, () -> new Mcv2(Mcv2.Settings.DEFAULT, null, 1, Mcv2.Verification.UNVERIFIED));

      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.UNVERIFIED);
      final Optional<byte[]> initialReference = encoder.getReference();
      final boolean initialReferenceAbsent = initialReference.isEmpty();
      assertTrue(initialReferenceAbsent);

      final Optional<Mcv2.Statistics> statistics = encoder.getStatistics();
      final boolean statisticsAbsent = statistics.isEmpty();
      assertTrue(statisticsAbsent);

      assertThrows(IllegalArgumentException.class, () -> encoder.setFrameBudget(-1));
      assertThrows(IllegalArgumentException.class, () -> encoder.setFrameLimit(-1));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 0, 1, 0));

      final IllegalArgumentException dimensionsException = assertThrows(IllegalArgumentException.class, () ->
        encoder.encode(new byte[3], 4097, 1, 0)
      );
      final String dimensionsMessage = dimensionsException.getMessage();
      assertEquals("Invalid dimensions!", dimensionsMessage);
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[2], 1, 1, 0));

      final IllegalArgumentException sizeException = assertThrows(IllegalArgumentException.class, () ->
        encoder.encode(new byte[4], 1, 1, 0)
      );
      final String sizeMessage = sizeException.getMessage();
      assertEquals("Picture size does not match the dimensions!", sizeMessage);
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 1, 1, -1));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 1, 1, 0x100000000L));
      assertThrows(NullPointerException.class, () -> encoder.encode(null, 1, 1, 0));
      assertThrows(NullPointerException.class, () -> encoder.setSettings(null));
      assertThrows(NullPointerException.class, () -> encoder.finish(null));

      encoder.encode(new byte[3], 1, 1, 0);
      assertThrows(IllegalArgumentException.class, () -> encoder.begin(new byte[3], 1, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> encoder.begin(new byte[3], 1, 1, 0x80000000L));

      final byte[] reference = encoder.getReference().orElseThrow();

      reference[0] = 42;
      final byte[] expected = new byte[3];
      final byte[] referenceAfterWrite = encoder.getReference().orElseThrow();
      assertArrayEquals(expected, referenceAfterWrite);
    }
  }
}
