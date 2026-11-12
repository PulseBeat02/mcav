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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.NearestDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.RandomDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.ColorMapNearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.NearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomNumberProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.UniformNoiseDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.XoroshiroRandomProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

final class RandomAndNearestDitherTest {

  private static final long SEED = 42L;
  private static final long FIRST_OUTPUT_BITS = 8119767394961995L;
  private static final long UNIT_STEPS = 1L << 53;

  private static final double TOLERANCE = 1.0e-12;

  private static final int DRAWS = 1_000;

  private static final int RANGE_MINIMUM = -2;
  private static final int RANGE_BOUND = 3;
  private static final double FRACTION_MINIMUM = 1.0;
  private static final double FRACTION_BOUND = 2.0;

  private static final int TOO_HEAVY_WEIGHT = 256;

  private static final int NOISE_IMAGE_WIDTH = 64;
  private static final int WIDE_IMAGE_WIDTH = 128;
  private static final int WIDE_IMAGE_HEIGHT = 64;
  private static final int SMALL_IMAGE_SIDE = 8;

  private static final int BLACK = 0xFF000000;
  private static final int WHITE = 0xFFFFFFFF;

  private static final String IMAGE_NULL_MESSAGE = "Image must not be null!";

  private static final String CHAINING_MESSAGE = "the builder returns itself for chaining";

  private static byte[] dither(final DitherAlgorithm algorithm, final int[] pixels, final int width) {
    final int height = pixels.length / width;
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, width, height)) {
      return algorithm.ditherIntoBytes(image);
    }
  }

  @Test
  void shouldPickTheNearestColorWhenRandomDitheringWithoutWeight() {
    final int[] pixels = DitherTestImages.gradient(NOISE_IMAGE_WIDTH, 4);
    final UniformNoiseDither random = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 0);
    final NearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    final byte[] randomIndices = dither(random, pixels, NOISE_IMAGE_WIDTH);
    final byte[] nearestIndices = dither(nearest, pixels, NOISE_IMAGE_WIDTH);
    final int weight = random.getWeight();
    assertArrayEquals(nearestIndices, randomIndices);
    assertEquals(0, weight);
  }

  @Test
  void shouldRandomDitherReproduciblyWithTheSameSeed() {
    final int[] pixels = DitherTestImages.gradient(NOISE_IMAGE_WIDTH, 16);
    final UniformNoiseDither first = new UniformNoiseDither(
      DitherTestImages.BLACK_WHITE,
      RandomDither.HEAVY_WEIGHT,
      new XoroshiroRandomProvider(7L)
    );
    final UniformNoiseDither second = new UniformNoiseDither(
      DitherTestImages.BLACK_WHITE,
      RandomDither.HEAVY_WEIGHT,
      new XoroshiroRandomProvider(7L)
    );
    final byte[] firstIndices = dither(first, pixels, NOISE_IMAGE_WIDTH);
    final byte[] secondIndices = dither(second, pixels, NOISE_IMAGE_WIDTH);
    final double ratio = DitherTestImages.whiteRatio(firstIndices);
    assertArrayEquals(firstIndices, secondIndices);

    final boolean halfWhite = ratio > 0.4 && ratio < 0.6;
    assertTrue(halfWhite, "ratio %s".formatted(ratio));
  }

  @Test
  void shouldWritePaletteColorsWhenRandomDitheringInPlace() {
    final int[] pixels = DitherTestImages.gradient(32, 4);
    final UniformNoiseDither random = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, RandomDither.LIGHT_WEIGHT);
    random.dither(pixels, 32);
    for (final int pixel : pixels) {
      final boolean paletteColor = pixel == BLACK || pixel == WHITE;
      assertTrue(paletteColor);
    }
  }

  @Test
  void shouldCreateTheConfiguredAlgorithmFromTheRandomBuilder() {
    final RandomDitherBuilder builder = DitherAlgorithm.random();
    final RandomDitherBuilder weighted = builder.weight(RandomDither.HEAVY_WEIGHT);
    final RandomDitherBuilder configured = weighted.palette(DitherTestImages.BLACK_WHITE);
    final RandomDither built = configured.build();
    final RandomDitherBuilder defaultBuilder = DitherAlgorithm.random();
    final RandomDither defaults = defaultBuilder.build();
    final DitherPalette palette = built.getPalette();
    final DitherPalette defaultPalette = defaults.getPalette();
    final UniformNoiseDither noise = assertInstanceOf(UniformNoiseDither.class, built);
    final UniformNoiseDither defaultNoise = assertInstanceOf(UniformNoiseDither.class, defaults);
    final int weight = noise.getWeight();
    final int defaultWeight = defaultNoise.getWeight();
    assertSame(builder, weighted, CHAINING_MESSAGE);
    assertSame(builder, configured);
    assertSame(DitherTestImages.BLACK_WHITE, palette);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, defaultPalette);
    assertEquals(RandomDither.HEAVY_WEIGHT, weight, "the builder passes the configured weight on");
    assertEquals(RandomDither.NORMAL_WEIGHT, defaultWeight, "and defaults to the normal weight");
  }

  @Test
  void shouldRejectInvalidSettingsInTheRandomBuilderAndAlgorithm() {
    final RandomDitherBuilder builder = DitherAlgorithm.random();
    assertThrows(IllegalArgumentException.class, () -> builder.weight(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.weight(TOO_HEAVY_WEIGHT));
    assertThrows(NullPointerException.class, () -> builder.palette(null));
    assertThrows(IllegalArgumentException.class, () -> new UniformNoiseDither(DitherTestImages.BLACK_WHITE, -1));
    assertThrows(IllegalArgumentException.class, () -> new UniformNoiseDither(DitherTestImages.BLACK_WHITE, TOO_HEAVY_WEIGHT));
    assertThrows(NullPointerException.class, () -> new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 1, null));
  }

  @Test
  void shouldRejectAMissingPaletteInTheNearestAndRandomConstructors() {
    final RandomNumberProvider provider = new XoroshiroRandomProvider(SEED);
    assertThrows(NullPointerException.class, () -> new ColorMapNearestDither(null));
    assertThrows(NullPointerException.class, () -> new UniformNoiseDither(null, RandomDither.NORMAL_WEIGHT));
    assertThrows(NullPointerException.class, () -> new UniformNoiseDither(null, RandomDither.NORMAL_WEIGHT, provider));
  }

  @Test
  void shouldReportAMissingImageBeforeReadingItsPixelsWhenRandomDithering() {
    final UniformNoiseDither random = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 0);
    final NullPointerException missingImage = assertThrows(NullPointerException.class, () -> random.ditherIntoBytes(null));
    final String message = missingImage.getMessage();
    assertEquals(IMAGE_NULL_MESSAGE, message);
  }

  @Test
  void shouldAcceptTheBoundaryWeights() {
    final RandomDitherBuilder builder = DitherAlgorithm.random();
    final RandomDitherBuilder lightest = builder.weight(0);
    final RandomDitherBuilder heaviest = builder.weight(255);
    final UniformNoiseDither lowest = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 0);
    final UniformNoiseDither highest = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 255);
    final int lowestWeight = lowest.getWeight();
    final int highestWeight = highest.getWeight();
    assertSame(builder, lightest, "a weight of 0 is allowed");
    assertSame(builder, heaviest, "and so is a weight of 255");
    assertEquals(0, lowestWeight);
    assertEquals(255, highestWeight, "the algorithm reports the weight it was given");
  }

  @Test
  void shouldCheckTheWeightInTheConstructorWithAProviderAsWell() {
    final RandomNumberProvider provider = new XoroshiroRandomProvider(1L);
    assertThrows(IllegalArgumentException.class, () -> new UniformNoiseDither(DitherTestImages.BLACK_WHITE, -1, provider));
    assertThrows(IllegalArgumentException.class, () -> new UniformNoiseDither(DitherTestImages.BLACK_WHITE, TOO_HEAVY_WEIGHT, provider));
  }

  @Test
  void shouldCheckTheBufferWhenDitheringInPlace() {
    final NearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    final UniformNoiseDither random = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 0);
    assertThrows(IllegalArgumentException.class, () -> nearest.dither(new int[3], 2));
    assertThrows(IllegalArgumentException.class, () -> random.dither(new int[3], 2));
    assertThrows(IllegalArgumentException.class, () -> nearest.dither(new int[4], 0));
    assertThrows(NullPointerException.class, () -> nearest.dither(null, 2));
    assertThrows(NullPointerException.class, () -> random.dither(null, 2));
  }

  @Test
  void shouldScaleTheDoublesToTheWidthOfTheRangeWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    final double scaled = provider.nextDouble(0.0, 4.0);
    final double unit = FIRST_OUTPUT_BITS / (double) UNIT_STEPS;
    final double expected = unit * 4.0;
    assertEquals(expected, scaled, TOLERANCE, "the unit value is multiplied by the width of the range, not divided by it");
  }

  @Test
  void shouldTranslateNonzeroBoundsWithoutChangingTheDistributionWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    final double actual = provider.nextDouble(-5.0, 7.0);
    final double unit = FIRST_OUTPUT_BITS / (double) UNIT_STEPS;
    final double expected = -5.0 + 12.0 * unit;
    assertEquals(expected, actual, TOLERANCE);
  }

  @Test
  void shouldPreserveTheQuantileWhenTheRangeOverflowsWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    final double minimum = -Double.MAX_VALUE;
    final double maximum = Double.MAX_VALUE / 2.0;
    final double actual = provider.nextDouble(minimum, maximum);
    // The independently recorded C reference output fixes this quantile; decimals avoid a double overflow in the convex combination.
    final BigDecimal numerator = BigDecimal.valueOf(FIRST_OUTPUT_BITS);
    final BigDecimal denominator = BigDecimal.valueOf(UNIT_STEPS);
    final BigDecimal unit = numerator.divide(denominator);
    final BigDecimal complement = BigDecimal.ONE.subtract(unit);
    final BigDecimal lower = new BigDecimal(minimum);
    final BigDecimal upper = new BigDecimal(maximum);
    final BigDecimal lowerPart = lower.multiply(complement);
    final BigDecimal upperPart = upper.multiply(unit);
    final BigDecimal weighted = lowerPart.add(upperPart);
    final double expected = weighted.doubleValue();
    final double tolerance = Math.ulp(expected) * 4.0;
    assertEquals(expected, actual, tolerance);
  }

  @Test
  void shouldReadTheBooleanFromTheSignOfTheOutputWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    final boolean firstDraw = provider.nextBoolean();
    final boolean secondDraw = provider.nextBoolean();
    final boolean thirdDraw = provider.nextBoolean();
    assertTrue(firstDraw, "the first output of seed 42 is negative, so its highest bit is set");
    assertFalse(secondDraw, "the second is positive");
    assertTrue(thirdDraw);
  }

  @Test
  void shouldSplitAtMiddleGrayWhenNearestDithering() {
    final int[] pixels = DitherTestImages.gradient(WIDE_IMAGE_WIDTH, WIDE_IMAGE_HEIGHT);
    final NearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    final byte[] indices = dither(nearest, pixels, WIDE_IMAGE_WIDTH);
    final byte firstIndex = indices[0];
    assertEquals(0, firstIndex);

    final byte darkerIndex = indices[63];
    assertEquals(0, darkerIndex, "126 is darker than the middle between black and white");

    final byte brighterIndex = indices[64];
    assertEquals(1, brighterIndex, "128 is brighter than the middle");

    final byte lastIndex = indices[127];
    assertEquals(1, lastIndex);
  }

  @Test
  void shouldReadEveryChannelFromItsOwnPlaceInThePackedColorWhenNearestDithering() {
    final DitherPalette blackRedGreen = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00);
    final ColorMapNearestDither nearest = new ColorMapNearestDither(blackRedGreen);
    final int[] pixels = { 0xFFFF0000, 0xFF00FF00 };
    nearest.dither(pixels, 2);
    final int[] expected = { 0xFFFF0000, 0xFF00FF00 };
    assertArrayEquals(expected, pixels, "a red pixel stays red and a green one green, so both channels are read correctly");
  }

  @Test
  void shouldMatchSerialWhenNearestDitheringOnASingleThread() {
    final int[] pixels = DitherTestImages.gradient(SMALL_IMAGE_SIDE, SMALL_IMAGE_SIDE);
    final ColorMapNearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    try (
      final ForkJoinPool pool = new ForkJoinPool(1);
      final ImageBuffer image = ImageBuffer.buffer(pixels, SMALL_IMAGE_SIDE, SMALL_IMAGE_SIDE)
    ) {
      final byte[] serial = nearest.ditherIntoBytes(image);
      final byte[] parallel = nearest.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchSerialWhenNearestDitheringALargeImageOnAWidePool() {
    final int[] pixels = DitherTestImages.gradient(1024, 1024);
    final ColorMapNearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    try (final ForkJoinPool pool = new ForkJoinPool(32); final ImageBuffer image = ImageBuffer.buffer(pixels, 1024, 1024)) {
      final byte[] serial = nearest.ditherIntoBytes(image);
      final byte[] parallel = nearest.ditherIntoBytes(image, pool);
      assertArrayEquals(serial, parallel);
    }
  }

  @Test
  void shouldMatchSerialWhenNearestDitheringInParallel() {
    final int[] large = DitherTestImages.gradient(WIDE_IMAGE_WIDTH, WIDE_IMAGE_HEIGHT);
    final int[] small = DitherTestImages.gradient(SMALL_IMAGE_SIDE, SMALL_IMAGE_SIDE);
    final ColorMapNearestDither nearest = new ColorMapNearestDither(DitherTestImages.BLACK_WHITE);
    final ForkJoinPool pool = new ForkJoinPool(2);
    try (
      final ImageBuffer largeImage = ImageBuffer.buffer(large, WIDE_IMAGE_WIDTH, WIDE_IMAGE_HEIGHT);
      final ImageBuffer smallImage = ImageBuffer.buffer(small, SMALL_IMAGE_SIDE, SMALL_IMAGE_SIDE)
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

    final NullPointerException missingImage = assertThrows(NullPointerException.class, () -> nearest.ditherIntoBytes(null));
    final NullPointerException missingParallelImage = assertThrows(NullPointerException.class, () -> nearest.ditherIntoBytes(null, pool));
    final String message = missingImage.getMessage();
    assertEquals(IMAGE_NULL_MESSAGE, message);

    final String parallelMessage = missingParallelImage.getMessage();
    assertEquals(IMAGE_NULL_MESSAGE, parallelMessage);
  }

  @Test
  void shouldNearestDitherInPlaceAndThroughBuilders() {
    final int[] pixels = { 0xFF101010, 0xFFF0F0F0 };
    final NearestDitherBuilder builder = DitherAlgorithm.nearest();
    final NearestDitherBuilder configured = builder.palette(DitherTestImages.BLACK_WHITE);
    final NearestDither nearest = configured.build();
    nearest.dither(pixels, 2);
    final NearestDither defaults = DitherAlgorithm.nearestColor();
    final DitherPalette defaultPalette = defaults.getPalette();
    final int[] expected = new int[] { BLACK, WHITE };
    assertSame(builder, configured, CHAINING_MESSAGE);
    assertArrayEquals(expected, pixels);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, defaultPalette);
    assertThrows(NullPointerException.class, () -> builder.palette(null));
  }

  @Test
  void shouldBeReproducibleWithTheSameSeedWithXoroshiro() {
    final XoroshiroRandomProvider first = new XoroshiroRandomProvider(SEED);
    final XoroshiroRandomProvider second = new XoroshiroRandomProvider(SEED);
    final XoroshiroRandomProvider other = new XoroshiroRandomProvider(43L);
    final int[] firstSequence = new int[DRAWS];
    final int[] otherSequence = new int[DRAWS];
    for (int draw = 0; draw < DRAWS; draw++) {
      final int value = first.nextInt(RANGE_MINIMUM, RANGE_BOUND);
      final int sameValue = second.nextInt(RANGE_MINIMUM, RANGE_BOUND);
      firstSequence[draw] = value;
      otherSequence[draw] = other.nextInt(RANGE_MINIMUM, RANGE_BOUND);
      final double fraction = first.nextDouble(FRACTION_MINIMUM, FRACTION_BOUND);
      final double sameFraction = second.nextDouble(FRACTION_MINIMUM, FRACTION_BOUND);
      other.nextDouble(FRACTION_MINIMUM, FRACTION_BOUND);
      final boolean drawn = first.nextBoolean();
      final boolean sameFlag = second.nextBoolean();
      other.nextBoolean();
      assertEquals(value, sameValue);
      assertEquals(fraction, sameFraction);
      assertEquals(drawn, sameFlag);
    }

    final boolean sameSequence = Arrays.equals(firstSequence, otherSequence);
    assertFalse(sameSequence, "different seeds differ at the same positions");

    final int firstNext = first.nextInt(0, Integer.MAX_VALUE);
    final int otherNext = other.nextInt(0, Integer.MAX_VALUE);
    assertNotEquals(firstNext, otherNext, "another seed produces another sequence");
  }

  @Test
  void shouldStayInRangeAndReachBothEndsWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    boolean sawMinimum = false;
    boolean sawMaximum = false;
    boolean sawTrue = false;
    boolean sawFalse = false;
    for (int draw = 0; draw < DRAWS; draw++) {
      final int value = provider.nextInt(RANGE_MINIMUM, RANGE_BOUND);
      final double fraction = provider.nextDouble(FRACTION_MINIMUM, FRACTION_BOUND);
      final boolean drawn = provider.nextBoolean();
      final boolean valueInRange = value >= RANGE_MINIMUM && value < RANGE_BOUND;
      assertTrue(valueInRange);
      final boolean fractionInRange = fraction >= FRACTION_MINIMUM && fraction < FRACTION_BOUND;
      assertTrue(fractionInRange);
      sawMinimum |= value == RANGE_MINIMUM;
      sawMaximum |= value == 2;
      sawTrue |= drawn;
      sawFalse |= !drawn;
    }

    final boolean reachedEveryOutcome = sawMinimum && sawMaximum && sawTrue && sawFalse;
    assertTrue(reachedEveryOutcome);
  }

  @Test
  void shouldNeverRoundToTheExclusiveDoubleBoundWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(3L);
    final double upper = Math.nextUp(1.0);
    final double value = provider.nextDouble(1.0, upper);
    assertEquals(1.0, value);
  }

  @Test
  void shouldHandleFiniteDoubleBoundsWhoseDifferenceOverflowsWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(0L);
    for (int sample = 0; sample < 100; sample++) {
      final double value = provider.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE);
      final boolean finite = Double.isFinite(value);
      assertTrue(finite);
      final boolean inRange = value >= -Double.MAX_VALUE && value < Double.MAX_VALUE;
      assertTrue(inRange);
    }
  }

  @Test
  void shouldRejectNonfiniteDoubleBoundsWithXoroshiro() {
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(0L);
    assertThrows(IllegalArgumentException.class, () -> provider.nextDouble(Double.NEGATIVE_INFINITY, 1.0));
    assertThrows(IllegalArgumentException.class, () -> provider.nextDouble(0.0, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> provider.nextDouble(Double.NaN, 1.0));
    assertThrows(IllegalArgumentException.class, () -> provider.nextDouble(0.0, Double.NaN));
  }

  @Test
  void shouldSeedUnseededGeneratorsDifferentlyWithXoroshiro() {
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
  void shouldDrawNoiseUpToTheWeightInBothDirectionsWhenRandomDithering() {
    final RandomNumberProvider provider = mock(RandomNumberProvider.class);
    when(provider.nextInt(-3, 4)).thenReturn(3);
    final UniformNoiseDither random = new UniformNoiseDither(DitherTestImages.BLACK_WHITE, 3, provider);
    final int[] pixels = { 0xFF7D7D7D };
    random.dither(pixels, 1);
    verify(provider, times(3)).nextInt(-3, 4);
    final int[] expected = new int[] { WHITE };
    assertArrayEquals(expected, pixels, "125 plus the largest noise of 3 reaches the white half");
  }

  @Test
  void shouldMatchAnIndependentReferenceImplementationWithXoroshiro() {
    // from a separate port of the reference C code of SplitMix64 and xoroshiro128+ (24, 16, 37); each is an output's top 53 bits
    final XoroshiroRandomProvider provider = new XoroshiroRandomProvider(SEED);
    final long[] expected = { 8119767394961995L, 693600059381773L, 4769685089197697L };
    for (final long expectedBits : expected) {
      final double value = provider.nextDouble(0.0, 1.0);
      final long bits = (long) (value * 0x1.0p53);
      assertEquals(expectedBits, bits);
    }
  }
}
