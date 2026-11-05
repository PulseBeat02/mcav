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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class Mcv2SettingsTest {

  private static final int LAMBDA = 72;
  private static final int FAST_LAMBDA = 55;

  @Test
  void shouldSearchQuarterSizeMotionOnlyInTheDefaultPreset() throws ReflectiveOperationException {
    for (final Settings settings : new Settings[] { Settings.DEFAULT, Settings.FAST }) {
      final Mcv2 encoder = new Mcv2(settings, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
      final Field factory = Mcv2.class.getDeclaredField("kernels");
      factory.setAccessible(true);
      final Supplier<?> original = (Supplier<?>) factory.get(encoder);
      final Set<Integer> widths = new HashSet<>();
      final Class<?> contract = Mcv2Internals.nested("Kernels");
      factory.set(encoder, (Supplier<?>) () -> this.recordingKernels(original.get(), contract, widths));
      encoder.encode(Mcv2Pictures.scene(128, 96, 0, 3), 128, 96, 0);
      encoder.encode(Mcv2Pictures.scene(128, 96, 2, 3), 128, 96, 1);
      final Set<Integer> expected = settings.isFast() ? Set.of(64, 128) : Set.of(32, 64, 128);
      assertEquals(expected, widths);
    }
  }

  private Object recordingKernels(final Object delegate, final Class<?> contract, final Set<Integer> widths) {
    return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract }, (_, method, arguments) ->
      this.invokeRecordingWidths(delegate, widths, method, arguments)
    );
  }

  private Object invokeRecordingWidths(final Object delegate, final Set<Integer> widths, final Method method, final Object[] arguments)
    throws ReflectiveOperationException {
    if (method.getName().equals("seeded")) {
      widths.add((Integer) arguments[1]);
    }

    method.setAccessible(true);
    return method.invoke(delegate, arguments);
  }

  @Test
  void shouldTryPatternsInPredictedFramesInBothPresets() {
    final byte[] stripes = new byte[8 * 8 * 3];
    for (int pixel = 0; pixel < 8 * 8; pixel++) {
      Arrays.fill(stripes, pixel * 3, pixel * 3 + 3, (byte) ((pixel & 1) == 0 ? 16 : 240));
    }

    for (final Settings settings : new Settings[] { Settings.DEFAULT, Settings.FAST }) {
      final Mcv2 encoder = new Mcv2(settings, ForkJoinPool.commonPool(), 3, Mcv2.Verification.VERIFIED);
      encoder.encode(new byte[stripes.length], 8, 8, 0);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(stripes, 8, 8, 1));
      final boolean keyframe = frame.isKeyframe();
      assertEquals(false, keyframe);
      final int leafCount = frame.getLeafCount();
      assertEquals(1, leafCount);
      final int mode = frame.getLeaf(0).mode();
      assertEquals(Mcv2Decoder.MODE_PATTERN, mode);
      final byte[] reference = encoder.getReference().orElseThrow();
      assertArrayEquals(stripes, reference);
    }
  }

  @Test
  void shouldPinThePresetSearchThresholds() {
    final Object rootSplitBits = Mcv2Internals.field(Mcv2.class, null, "ROOT_SPLIT_BITS");
    assertEquals(150, rootSplitBits);

    final Object normalSteadySplitBits = Mcv2Internals.field(Mcv2.class, null, "NORMAL_STEADY_SPLIT_BITS");
    assertEquals(450, normalSteadySplitBits);

    final Object fastSteadySplitBits = Mcv2Internals.field(Mcv2.class, null, "FAST_STEADY_SPLIT_BITS");
    assertEquals(900, fastSteadySplitBits);

    final Object normalFineSplitBits = Mcv2Internals.field(Mcv2.class, null, "NORMAL_FINE_SPLIT_BITS");
    assertEquals(300, normalFineSplitBits);

    final Object fastFineSplitBits = Mcv2Internals.field(Mcv2.class, null, "FAST_FINE_SPLIT_BITS");
    assertEquals(600, fastFineSplitBits);

    final Object normalSkipBits = Mcv2Internals.field(Mcv2.class, null, "NORMAL_SKIP_BITS");
    assertEquals(28, normalSkipBits);

    final Object fastSkipBits = Mcv2Internals.field(Mcv2.class, null, "FAST_SKIP_BITS");
    assertEquals(60, fastSkipBits);
  }

  @Test
  void shouldKeepTheTwoLivePresetsAsData() {
    final Settings expectedDefault = Settings.of(LAMBDA, false);
    assertEquals(expectedDefault, Settings.DEFAULT);

    final Settings expectedFast = Settings.of(FAST_LAMBDA, true);
    assertEquals(expectedFast, Settings.FAST);
  }

  @Test
  void shouldCopyWithOneValueChanged() {
    final Settings settings = Settings.of(LAMBDA, true);
    final Settings expected = Settings.of(0, true);
    final Settings copy = settings.withLambda(0);
    assertEquals(expected, copy);
  }

  @Test
  void shouldRefuseInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(-1));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.NaN));
  }

  @Test
  void shouldCompareSettingsByEveryPart() {
    final Mcv2.Settings settings = Mcv2.Settings.of(LAMBDA, false);
    EqualityAssertions.assertEqualityContract(
      settings,
      Mcv2.Settings.of(LAMBDA, false),
      Mcv2.Settings.of(FAST_LAMBDA, false),
      Mcv2.Settings.of(LAMBDA, true)
    );
  }

  @Test
  void shouldHashSettingsByEveryPart() {
    final Mcv2.Settings settings = Mcv2.Settings.of(LAMBDA, true);

    final int hash = settings.hashCode();
    final int expected = Objects.hash((double) LAMBDA, true);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeSettingsWithEveryPart() {
    final String description = Mcv2.Settings.FAST.toString();
    assertEquals("Settings{lambda=55.0, fast=true}", description);
  }

  @Test
  void shouldCompareStatisticsByEveryPart() {
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);
    EqualityAssertions.assertEqualityContract(
      statistics,
      Mcv2.Statistics.of(1, true, 2, 3, 4),
      Mcv2.Statistics.of(9, true, 2, 3, 4),
      Mcv2.Statistics.of(1, false, 2, 3, 4),
      Mcv2.Statistics.of(1, true, 9, 3, 4),
      Mcv2.Statistics.of(1, true, 2, 9, 4),
      Mcv2.Statistics.of(1, true, 2, 3, 9)
    );
  }

  @Test
  void shouldHashStatisticsByEveryPart() {
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);

    final int hash = statistics.hashCode();
    final int expected = Objects.hash(1, true, 2, 3L, 4.0);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeStatisticsWithEveryPart() {
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);

    final String description = statistics.toString();
    assertEquals("Statistics{bytes=1, keyframe=true, leaves=2, nanoseconds=3, lambda=4.0}", description);
  }

  @Test
  void shouldCompareEncodedFramesByEveryPart() {
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);
    final Mcv2.Statistics otherStatistics = Mcv2.Statistics.of(9, true, 2, 3, 4);
    final Mcv2.Encoded encoded = Mcv2.Encoded.of(new byte[] { 1 }, statistics);
    EqualityAssertions.assertEqualityContract(
      encoded,
      Mcv2.Encoded.of(new byte[] { 1 }, statistics),
      Mcv2.Encoded.of(new byte[] { 9 }, statistics),
      Mcv2.Encoded.of(new byte[] { 1 }, otherStatistics)
    );
  }

  @Test
  void shouldHashEncodedFramesByEveryPart() {
    final byte[] data = { 1 };
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);
    final Mcv2.Encoded encoded = Mcv2.Encoded.of(data, statistics);

    final int hash = encoded.hashCode();
    final int dataHash = Arrays.hashCode(data);
    final int expected = Objects.hash(dataHash, statistics);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeEncodedFramesWithTheirLengthAndStatistics() {
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);
    final Mcv2.Encoded encoded = Mcv2.Encoded.of(new byte[] { 1 }, statistics);

    final String description = encoded.toString();
    assertEquals("Encoded{dataBytes=1, statistics=Statistics{bytes=1, keyframe=true, leaves=2, nanoseconds=3, lambda=4.0}}", description);
  }

  @Test
  void shouldRefuseEncodedFramesWithoutParts() {
    final byte[] data = { 1 };
    final Mcv2.Statistics statistics = Mcv2.Statistics.of(1, true, 2, 3, 4);

    assertThrows(NullPointerException.class, () -> Mcv2.Encoded.of(null, statistics));
    assertThrows(NullPointerException.class, () -> Mcv2.Encoded.of(data, null));
  }
}
