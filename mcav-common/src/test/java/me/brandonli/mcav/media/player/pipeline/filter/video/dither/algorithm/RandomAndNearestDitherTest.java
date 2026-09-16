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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilderImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomNumberProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.XoroshiroRandomProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

/**
 * Tests the random and nearest color algorithms, their builders and {@link XoroshiroRandomProvider}.
 */
final class RandomAndNearestDitherTest {

  private static byte[] dither(final DitherAlgorithm algorithm, final int[] pixels, final int width) {
    final int height = pixels.length / width;
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, width, height)) {
      return algorithm.ditherIntoBytes(image);
    }
  }

  @Test
  void randomDitherWithoutWeightIsNearestColor() {
    final int[] pixels = DitherTestImages.gradient(64, 4);
    final RandomDitherImpl random = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 0);
    final NearestDither nearest = new NearestDitherImpl(DitherTestImages.BLACK_WHITE);
    final byte[] randomIndices = dither(random, pixels, 64);
    final byte[] nearestIndices = dither(nearest, pixels, 64);
    final int weight = random.getWeight();
    assertArrayEquals(nearestIndices, randomIndices);
    assertEquals(0, weight);
  }

  @Test
  void randomDitherIsReproducibleWithTheSameSeed() {
    final int[] pixels = DitherTestImages.gradient(64, 16);
    final RandomDitherImpl first = new RandomDitherImpl(
      DitherTestImages.BLACK_WHITE,
      RandomDither.HEAVY_WEIGHT,
      new XoroshiroRandomProvider(7L)
    );
    final RandomDitherImpl second = new RandomDitherImpl(
      DitherTestImages.BLACK_WHITE,
      RandomDither.HEAVY_WEIGHT,
      new XoroshiroRandomProvider(7L)
    );
    final byte[] firstIndices = dither(first, pixels, 64);
    final byte[] secondIndices = dither(second, pixels, 64);
    final double ratio = DitherTestImages.whiteRatio(firstIndices);
    assertArrayEquals(firstIndices, secondIndices);
    assertTrue(ratio > 0.4 && ratio < 0.6, "ratio " + ratio);
  }

  @Test
  void randomDitherInPlaceWritesPaletteColors() {
    final int[] pixels = DitherTestImages.gradient(32, 4);
    final RandomDitherImpl random = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, RandomDither.LIGHT_WEIGHT);
    random.dither(pixels, 32);
    for (final int pixel : pixels) {
      final boolean paletteColor = pixel == 0xFF000000 || pixel == 0xFFFFFFFF;
      assertTrue(paletteColor);
    }
  }

  @Test
  void randomBuilderCreatesTheConfiguredAlgorithm() {
    final RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> builder = DitherAlgorithm.random();
    final RandomDitherBuilderImpl weighted = builder.withWeight(RandomDither.NORMAL_WEIGHT);
    final RandomDitherBuilderImpl configured = weighted.withPalette(DitherTestImages.BLACK_WHITE);
    final RandomDither built = configured.build();
    final RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> defaultBuilder = DitherAlgorithm.random();
    final RandomDither defaults = defaultBuilder.build();
    final DitherPalette palette = built.getPalette();
    final DitherPalette defaultPalette = defaults.getPalette();
    assertSame(builder, weighted, "the builder returns itself for chaining");
    assertSame(builder, configured);
    assertSame(DitherTestImages.BLACK_WHITE, palette);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, defaultPalette);
  }

  @Test
  void randomBuilderAndAlgorithmRejectInvalidSettings() {
    final RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> builder = DitherAlgorithm.random();
    assertThrows(IllegalArgumentException.class, () -> builder.withWeight(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.withWeight(256));
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
    assertThrows(IllegalArgumentException.class, () -> new RandomDitherImpl(DitherTestImages.BLACK_WHITE, -1));
    assertThrows(IllegalArgumentException.class, () -> new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 256));
    assertThrows(NullPointerException.class, () -> new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 1, null));
  }

  @Test
  void acceptsTheBoundaryWeights() {
    final RandomDitherBuilder<RandomDither, RandomDitherBuilderImpl> builder = DitherAlgorithm.random();
    final RandomDitherBuilderImpl lightest = builder.withWeight(0);
    final RandomDitherBuilderImpl heaviest = builder.withWeight(255);
    final RandomDitherImpl lowest = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 0);
    final RandomDitherImpl highest = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 255);
    final int lowestWeight = lowest.getWeight();
    final int highestWeight = highest.getWeight();
    assertSame(builder, lightest, "a weight of 0 is allowed");
    assertSame(builder, heaviest, "and so is a weight of 255");
    assertEquals(0, lowestWeight);
    assertEquals(255, highestWeight, "the algorithm reports the weight it was given");
  }

  @Test
  void theConstructorWithAProviderChecksItsWeightAsWell() {
    final RandomNumberProvider provider = new XoroshiroRandomProvider(1L);
    assertThrows(IllegalArgumentException.class, () -> new RandomDitherImpl(DitherTestImages.BLACK_WHITE, -1, provider));
    assertThrows(IllegalArgumentException.class, () -> new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 256, provider));
  }

  @Test
  void ditheringInPlaceChecksItsBuffer() {
    final NearestDither nearest = new NearestDitherImpl(DitherTestImages.BLACK_WHITE);
    final RandomDitherImpl random = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 0);
    assertThrows(IllegalArgumentException.class, () -> nearest.dither(new int[3], 2));
    assertThrows(IllegalArgumentException.class, () -> random.dither(new int[3], 2));
    assertThrows(IllegalArgumentException.class, () -> nearest.dither(new int[4], 0));
    assertThrows(NullPointerException.class, () -> nearest.dither(null, 2));
    assertThrows(NullPointerException.class, () -> random.dither(null, 2));
  }

  @Test
  void xoroshiroScalesItsDoublesToTheWidthOfTheRange() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(42L);
    final double scaled = provider.nextDouble(0.0, 4.0);
    // the first output of seed 42 has the unit value 8119767394961995 / 2^53, see the reference test below
    final double unit = 8119767394961995L / (double) (1L << 53);
    final double expected = unit * 4.0;
    assertEquals(expected, scaled, 1.0e-12, "the unit value is multiplied by the width of the range, not divided by it");
  }

  @Test
  void xoroshiroReadsItsBooleanFromTheSignOfItsOutput() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(42L);
    final boolean first = provider.nextBoolean();
    final boolean second = provider.nextBoolean();
    final boolean third = provider.nextBoolean();
    assertTrue(first, "the first output of seed 42 is negative, so its highest bit is set");
    assertFalse(second, "the second is positive");
    assertTrue(third);
  }

  @Test
  void nearestDitherSplitsAtMidGray() {
    final int[] pixels = DitherTestImages.gradient(128, 64);
    final NearestDither nearest = new NearestDitherImpl(DitherTestImages.BLACK_WHITE);
    final byte[] indices = dither(nearest, pixels, 128);
    assertEquals(0, indices[0]);
    assertEquals(0, indices[63], "126 is darker than the middle between black and white");
    assertEquals(1, indices[64], "128 is brighter than the middle");
    assertEquals(1, indices[127]);
  }

  @Test
  void nearestDitherReadsEveryChannelFromItsOwnPlaceInThePackedColor() {
    final DitherPalette blackRedGreen = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00);
    final NearestDitherImpl nearest = new NearestDitherImpl(blackRedGreen);
    final int[] pixels = { 0xFFFF0000, 0xFF00FF00 };
    nearest.dither(pixels, 2);
    final int[] expected = { 0xFFFF0000, 0xFF00FF00 };
    assertArrayEquals(expected, pixels, "a red pixel stays red and a green one green, so both channels are read correctly");
  }

  @Test
  void nearestDitherInParallelMatchesSerial() {
    final int[] large = DitherTestImages.gradient(128, 64);
    final int[] small = DitherTestImages.gradient(8, 8);
    final NearestDitherImpl nearest = new NearestDitherImpl(DitherTestImages.BLACK_WHITE);
    final ForkJoinPool pool = new ForkJoinPool(2);
    try (
      final ImageBuffer largeImage = ImageBuffer.buffer(large, 128, 64);
      final ImageBuffer smallImage = ImageBuffer.buffer(small, 8, 8)
    ) {
      final byte[] largeSerial = nearest.ditherIntoBytes(largeImage);
      final byte[] largeParallel = nearest.ditherIntoBytes(largeImage, pool);
      final byte[] smallSerial = nearest.ditherIntoBytes(smallImage);
      final byte[] smallParallel = nearest.ditherIntoBytes(smallImage, pool);
      assertArrayEquals(largeSerial, largeParallel);
      assertArrayEquals(smallSerial, smallParallel);
      assertThrows(NullPointerException.class, () -> nearest.ditherIntoBytes(largeImage, null));
    } finally {
      pool.shutdownNow();
    }
    assertThrows(NullPointerException.class, () -> nearest.ditherIntoBytes(null, pool));
  }

  @Test
  void nearestDitherInPlaceAndBuilders() {
    final int[] pixels = { 0xFF101010, 0xFFF0F0F0 };
    final NearestDitherBuilder<NearestDither, NearestDitherBuilderImpl> builder = DitherAlgorithm.nearest();
    final NearestDitherBuilderImpl configured = builder.withPalette(DitherTestImages.BLACK_WHITE);
    final NearestDither nearest = configured.build();
    nearest.dither(pixels, 2);
    final NearestDither defaults = DitherAlgorithm.nearestColor();
    final DitherPalette defaultPalette = defaults.getPalette();
    assertSame(builder, configured, "the builder returns itself for chaining");
    assertArrayEquals(new int[] { 0xFF000000, 0xFFFFFFFF }, pixels);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, defaultPalette);
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
  }

  @Test
  void xoroshiroIsReproducibleWithTheSameSeed() {
    final XoroshiroRandomProvider first = new XoroshiroRandomProvider(42L);
    final XoroshiroRandomProvider second = new XoroshiroRandomProvider(42L);
    final XoroshiroRandomProvider other = new XoroshiroRandomProvider(43L);
    for (int draw = 0; draw < 1_000; draw++) {
      final int value = first.nextInt(-2, 3);
      final int sameValue = second.nextInt(-2, 3);
      final double fraction = first.nextDouble(1.0, 2.0);
      final double sameFraction = second.nextDouble(1.0, 2.0);
      final boolean flag = first.nextBoolean();
      final boolean sameFlag = second.nextBoolean();
      assertEquals(value, sameValue);
      assertEquals(fraction, sameFraction);
      assertEquals(flag, sameFlag);
    }

    final int firstNext = first.nextInt(0, Integer.MAX_VALUE);
    final int otherNext = other.nextInt(0, Integer.MAX_VALUE);
    assertNotEquals(firstNext, otherNext, "another seed produces another sequence");
  }

  @Test
  void xoroshiroStaysInRangeAndReachesBothEnds() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(42L);
    boolean sawMinimum = false;
    boolean sawMaximum = false;
    boolean sawTrue = false;
    boolean sawFalse = false;
    for (int draw = 0; draw < 1_000; draw++) {
      final int value = provider.nextInt(-2, 3);
      final double fraction = provider.nextDouble(1.0, 2.0);
      final boolean flag = provider.nextBoolean();
      assertTrue(value >= -2 && value < 3);
      assertTrue(fraction >= 1.0 && fraction < 2.0);
      sawMinimum |= value == -2;
      sawMaximum |= value == 2;
      sawTrue |= flag;
      sawFalse |= !flag;
    }
    assertTrue(sawMinimum && sawMaximum && sawTrue && sawFalse);
  }

  @Test
  void xoroshiroSeedsUnseededGeneratorsDifferently() {
    final XoroshiroRandomProvider first = new XoroshiroRandomProvider();
    final XoroshiroRandomProvider second = new XoroshiroRandomProvider();
    final double firstValue = first.nextDouble(0.0, 1.0);
    final double secondValue = second.nextDouble(0.0, 1.0);
    final boolean notANumber = Double.isNaN(firstValue);
    assertNotEquals(firstValue, secondValue);
    assertThrows(IllegalArgumentException.class, () -> first.nextInt(1, 1));
    assertThrows(IllegalArgumentException.class, () -> first.nextDouble(1.0, 1.0));
    assertFalse(notANumber);
  }

  @Test
  void randomDitherDrawsNoiseUpToTheWeightInBothDirections() {
    final RandomNumberProvider provider = mock(RandomNumberProvider.class);
    when(provider.nextInt(-3, 4)).thenReturn(3);
    final RandomDitherImpl random = new RandomDitherImpl(DitherTestImages.BLACK_WHITE, 3, provider);
    final int[] pixels = { 0xFF7D7D7D };
    random.dither(pixels, 1);
    verify(provider, times(3)).nextInt(-3, 4);
    assertArrayEquals(new int[] { 0xFFFFFFFF }, pixels, "125 plus the largest noise of 3 reaches the white half");
  }

  @Test
  void xoroshiroMatchesAnIndependentReferenceImplementation() {
    // computed with a separate implementation of the reference C code of SplitMix64 and xoroshiro128+ (24, 16, 37):
    // the state is the first two outputs of SplitMix64 seeded with 42, and each value is the top 53 bits of an output
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(42L);
    final long[] expected = { 8119767394961995L, 693600059381773L, 4769685089197697L };
    for (final long expectedBits : expected) {
      final double value = provider.nextDouble(0.0, 1.0);
      final long bits = (long) (value * 0x1.0p53);
      assertEquals(expectedBits, bits);
    }
  }
}
