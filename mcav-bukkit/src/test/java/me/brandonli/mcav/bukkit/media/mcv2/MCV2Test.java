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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Test;

final class MCV2Test {

  @Test
  void writesTheHandComputedUniformKeyframeAndPredictedGolden() throws Mcv2Exception {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, true);
      final byte[] picture = { 10, 20, 30 };
      final byte[] expected = HexFormat.of().parseHex("4d4356320301000001000100010000000100000038000000380000000a141e00" + "000000000000000000000000000000000000000000000000");
      assertArrayEquals(expected, encoder.encode(picture, 1, 1, 1));
      final byte[] predicted = expected.clone();
      predicted[5] = 0; predicted[12] = 2; predicted[28] = 0; predicted[29] = 0; predicted[30] = 0;
      assertArrayEquals(predicted, encoder.encode(picture, 1, 1, 2));
      assertArrayEquals(picture, Mcv2Decoder.decode(predicted, picture, 1));
      assertEquals(56, encoder.getStats().bytes());
      assertFalse(encoder.getStats().keyframe());
      assertEquals(72, encoder.getStats().lambda());
    }
  }

  @Test
  void reconstructionEqualsDecodeForEveryPresetAndOddSize() throws Mcv2Exception {
    final Random random = new Random(0x4D435632);
    for (final MCV2.Settings settings : new MCV2.Settings[] { MCV2.Settings.DEFAULT, MCV2.Settings.FAST, MCV2.Settings.ADAPTIVE }) {
      try (final ForkJoinPool pool = new ForkJoinPool(4)) {
        final MCV2 encoder = new MCV2(settings, pool, 4, true);
        byte[] reference = null;
        for (int index = 0; index < 12; index++) {
          final byte[] picture = new byte[65 * 37 * 3];
          random.nextBytes(picture);
          final byte[] data = encoder.encode(picture, 65, 37, index);
          final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
          reference = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
          assertArrayEquals(reference, encoder.getReference());
          assertEquals(index, frame.getFrameId());
          assertTrue(data.length <= Mcv2Decoder.MAX_FRAME_BYTES);
          if (!frame.isKeyframe()) { assertEquals(index - 1, frame.getReferenceId()); }
        }
      }
    }
  }

  @Test
  void writesIdenticalBytesOnOneThroughFourThreads() {
    final byte[][] source = new byte[5][128 * 96 * 3];
    final Random random = new Random(7719);
    random.nextBytes(source[0]);
    for (int index = 1; index < source.length; index++) {
      System.arraycopy(source[index - 1], 0, source[index], 0, source[index].length);
      for (int channel = 0; channel < source[index].length; channel += 19) { source[index][channel]++; }
    }
    for (final MCV2.Settings settings : new MCV2.Settings[] { MCV2.Settings.DEFAULT, MCV2.Settings.FAST, MCV2.Settings.ADAPTIVE }) {
      final byte[][] golden = new byte[source.length][];
      for (int threads = 1; threads <= 4; threads++) {
        try (final ForkJoinPool pool = new ForkJoinPool(threads)) {
          final MCV2 encoder = new MCV2(settings, pool, threads, true);
          for (int index = 0; index < source.length; index++) {
            final byte[] actual = encoder.encode(source[index], 128, 96, index);
            if (threads == 1) { golden[index] = actual; }
            else { assertArrayEquals(golden[index], actual); }
          }
        }
      }
    }
  }

  @Test
  void retriesAndFallsBackFromTheSameReference() throws Mcv2Exception {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT.withLambda(0), pool, 2, true);
      final byte[] source = new byte[63 * 65 * 3];
      new Random(17).nextBytes(source);
      encoder.setFrameLimit(56);
      final byte[] first = encoder.encode(source, 63, 65, 0);
      final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(first);
      for (int index = 0; index < parsed.getLeafCount(); index++) { assertEquals(32, parsed.getLeaf(index).size()); }
      assertTrue(first.length <= Mcv2Decoder.MAX_FRAME_BYTES);
      final byte[] reference = encoder.getReference();
      source[0]++;
      final byte[] second = encoder.encode(source, 63, 65, 1);
      assertArrayEquals(reference, encoder.getReference());
      assertEquals(0, Mcv2Decoder.parse(second).getReferenceId());
      assertArrayEquals(reference, Mcv2Decoder.decode(second, reference, 0));
    }
  }

  @Test
  void exhaustedBudgetUsesMeanSolidsAndThenSkip() throws Mcv2Exception {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 2, true);
      encoder.setFrameBudget(1);
      final byte[] source = new byte[32 * 32 * 3];
      for (int pixel = 0; pixel < 32 * 32; pixel++) { source[pixel * 3] = (byte) (pixel % 32 * 2); }
      final byte[] first = encoder.encode(source, 32, 32, 0);
      final byte[] decoded = Mcv2Decoder.decode(first, null, 0);
      for (int pixel = 0; pixel < 32 * 32; pixel++) { assertEquals(31, decoded[pixel * 3]); }
      assertEquals(56, first.length);
      source[0] = 10;
      assertEquals(56, encoder.encode(source, 32, 32, 1).length);
      assertArrayEquals(decoded, encoder.getReference());
    }
  }

  @Test
  void keyframesFollowRequestsSizesIntervalsCutsAndWrappedIds() throws Mcv2Exception {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(new MCV2.Settings(72, 3, false, false), pool, 1, true);
      final byte[] black = new byte[3];
      assertTrue(Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0xFFFFFFFEL)).isKeyframe());
      assertFalse(Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0xFFFFFFFFL)).isKeyframe());
      assertFalse(Mcv2Decoder.parse(encoder.encode(black, 1, 1, 0)).isKeyframe());
      assertTrue(Mcv2Decoder.parse(encoder.encode(black, 1, 1, 1)).isKeyframe());
      encoder.requestKeyframe();
      assertTrue(Mcv2Decoder.parse(encoder.encode(black, 1, 1, 2)).isKeyframe());
      assertTrue(Mcv2Decoder.parse(encoder.encode(new byte[6], 2, 1, 3)).isKeyframe());
      final byte[] white = new byte[6]; Arrays.fill(white, (byte) 255);
      assertTrue(Mcv2Decoder.parse(encoder.encode(white, 2, 1, 4)).isKeyframe());
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(white, 2, 1, 4));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(white, 2, 1, 0x80000004L));
    }
  }

  @Test
  void switchingSettingsKeepsTheReferenceAndAdaptiveUsesSourceMotion() throws Mcv2Exception {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.ADAPTIVE, pool, 1, true);
      final byte[] source = new byte[32 * 32 * 3];
      encoder.encode(source, 32, 32, 0);
      Arrays.fill(source, (byte) 10); encoder.encode(source, 32, 32, 1);
      Arrays.fill(source, (byte) 20); encoder.encode(source, 32, 32, 2);
      final double raised = 55 * Math.pow(10 / 4.6, 0.79);
      assertEquals(raised, encoder.getStats().lambda());
      encoder.switchTo(MCV2.Settings.FAST.withLambda(30));
      assertEquals(MCV2.Settings.FAST.withLambda(30), encoder.getSettings());
      final Mcv2Decoder.Frame next = Mcv2Decoder.parse(encoder.encode(source, 32, 32, 3));
      assertFalse(next.isKeyframe()); assertEquals(2, next.getReferenceId());
      assertEquals(30 * Math.pow(10 / 4.6, 0.79), encoder.getStats().lambda());
    }
  }

  @Test
  void pipelineMatchesSerialAndEnforcesFinishOrderAndOwnership() {
    try (final ForkJoinPool pool = new ForkJoinPool(2)) {
      final MCV2 serial = new MCV2(MCV2.Settings.DEFAULT, pool, 2, true);
      final MCV2 pipelined = new MCV2(MCV2.Settings.DEFAULT, pool, 2, true);
      final byte[][] source = new byte[3][32 * 32 * 3];
      final Random random = new Random(171);
      for (final byte[] picture : source) { random.nextBytes(picture); }
      final MCV2.Pending first = pipelined.begin(source[0], 32, 32, 0);
      final MCV2.Pending second = pipelined.begin(source[1], 32, 32, 1);
      assertThrows(IllegalStateException.class, () -> pipelined.begin(source[2], 32, 32, 2));
      assertThrows(IllegalStateException.class, () -> pipelined.finish(second));
      assertThrows(IllegalArgumentException.class, () -> serial.finish(first));
      assertArrayEquals(serial.encode(source[0], 32, 32, 0), pipelined.finish(first).getData());
      final MCV2.Pending third = pipelined.begin(source[2], 32, 32, 2);
      assertArrayEquals(serial.encode(source[1], 32, 32, 1), pipelined.finish(second).getData());
      assertArrayEquals(serial.encode(source[2], 32, 32, 2), pipelined.finish(third).getData());
      assertThrows(IllegalStateException.class, () -> pipelined.finish(third));
    }
  }

  @Test
  void verificationFailurePermanentlyStopsBothPipelineStages() throws ReflectiveOperationException {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, true);
      final MCV2.Pending first = encoder.begin(new byte[3], 1, 1, 0);
      final MCV2.Pending second = encoder.begin(new byte[3], 1, 1, 1);
      final Field picture = MCV2.Pending.class.getDeclaredField("picture");
      picture.setAccessible(true);
      ((byte[]) picture.get(first))[0] = 1;
      assertThrows(IllegalStateException.class, () -> encoder.finish(first));
      assertThrows(IllegalStateException.class, () -> encoder.finish(second));
      assertThrows(IllegalStateException.class, () -> encoder.begin(new byte[3], 1, 1, 2));
      assertNull(encoder.getStats());
    }
  }

  @Test
  void checksSettingsInputsAndReferenceOwnership() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      assertThrows(IllegalArgumentException.class, () -> new MCV2.Settings(-1, 1, false, false));
      assertThrows(IllegalArgumentException.class, () -> new MCV2.Settings(Double.NaN, 1, false, false));
      assertThrows(IllegalArgumentException.class, () -> new MCV2.Settings(Double.POSITIVE_INFINITY, 1, false, false));
      assertThrows(IllegalArgumentException.class, () -> new MCV2.Settings(1, 0, false, false));
      assertThrows(IllegalArgumentException.class, () -> new MCV2(MCV2.Settings.DEFAULT, pool, 0, false));
      assertThrows(NullPointerException.class, () -> new MCV2(null, pool, 1, false));
      assertThrows(NullPointerException.class, () -> new MCV2(MCV2.Settings.DEFAULT, null, 1, false));
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, false);
      assertNull(encoder.getReference()); assertNull(encoder.getStats());
      assertThrows(IllegalArgumentException.class, () -> encoder.setFrameBudget(-1));
      assertThrows(IllegalArgumentException.class, () -> encoder.setFrameLimit(-1));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 0, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 4097, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[2], 1, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 1, 1, -1));
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[3], 1, 1, 0x100000000L));
      assertThrows(NullPointerException.class, () -> encoder.encode(null, 1, 1, 0));
      assertThrows(NullPointerException.class, () -> encoder.switchTo(null));
      assertThrows(NullPointerException.class, () -> encoder.finish(null));
      encoder.encode(new byte[3], 1, 1, 0);
      final byte[] reference = encoder.getReference(); assertNotNull(reference); reference[0] = 42;
      assertArrayEquals(new byte[3], encoder.getReference());
    }
  }
}
