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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.AtkinsonDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.BurkesDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.DiffusionKernel;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FloydDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.JarvisJudiceNinkeDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StevensonArceDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StuckiDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class ErrorDiffusionDitherTest {

  private static final int WIDTH = 256;
  private static final int HEIGHT = 64;

  private static final int BLACK = 0xFF000000;
  private static final int WHITE = 0xFFFFFFFF;

  private static final int MIDDLE_GRAY_LEVEL = 128;

  private static final int TEMPORAL_THRESHOLD = 20;
  private static final int ERROR_THRESHOLD = 7;
  private static final float ERROR_STRENGTH = 0.5f;

  private static final Map<ErrorDiffusionDitherBuilder.Algorithm, DiffusionKernel> KERNELS = Map.of(
    ErrorDiffusionDitherBuilder.Algorithm.ATKINSON,
    DiffusionKernel.ATKINSON,
    ErrorDiffusionDitherBuilder.Algorithm.BURKES,
    DiffusionKernel.BURKES,
    ErrorDiffusionDitherBuilder.Algorithm.FILTER_LITE,
    DiffusionKernel.FILTER_LITE,
    ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG,
    DiffusionKernel.FLOYD_STEINBERG,
    ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG,
    DiffusionKernel.FLOYD_STEINBERG,
    ErrorDiffusionDitherBuilder.Algorithm.JARVIS_JUDICE_NINKE,
    DiffusionKernel.JARVIS_JUDICE_NINKE,
    ErrorDiffusionDitherBuilder.Algorithm.STEVENSON_ARCE,
    DiffusionKernel.STEVENSON_ARCE,
    ErrorDiffusionDitherBuilder.Algorithm.STUCKI,
    DiffusionKernel.STUCKI
  );

  private static ErrorDiffusionDither build(final ErrorDiffusionDitherBuilder.Algorithm algorithm) {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder withAlgorithm = builder.withAlgorithm(algorithm);
    final ErrorDiffusionDitherBuilder configured = withAlgorithm.withPalette(DitherTestImages.BLACK_WHITE);
    return configured.build();
  }

  private static int gray(final int value) {
    return 0xFF000000 | (value << 16) | (value << 8) | value;
  }

  @Test
  void shouldBrightenTheNextPixelWithAnAmplifiedPositiveErrorInBothOutputForms() {
    final DiffusionKernel kernel = new DiffusionKernel("Amplified right", 1, new int[][] { { 1, 0, Integer.MAX_VALUE } });
    final ErrorDiffusionDither dither = new ErrorDiffusionDither(DitherTestImages.BLACK_WHITE, kernel) {};
    final int[] source = { 0xFF646464, 0xFF646464 };
    final int[] inPlace = source.clone();
    dither.dither(inPlace, 2);
    assertArrayEquals(new int[] { BLACK, WHITE }, inPlace);

    try (final ImageBuffer image = ImageBuffer.buffer(source, 2, 1)) {
      final byte[] ditheredIndices = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 0, 1 }, ditheredIndices);

      final int[] untouchedPixels = image.getPixels();
      assertArrayEquals(new int[] { 0xFF646464, 0xFF646464 }, untouchedPixels);
    }
  }

  @Test
  void shouldPreserveTheAverageBrightnessWithEveryAlgorithm() {
    final int[] pixels = DitherTestImages.gradient(WIDTH, HEIGHT);
    for (final ErrorDiffusionDitherBuilder.Algorithm algorithm : ErrorDiffusionDitherBuilder.Algorithm.values()) {
      final ErrorDiffusionDither dither = build(algorithm);
      this.assertPreservesTheAverageBrightness(algorithm, dither, pixels);
    }
  }

  private void assertPreservesTheAverageBrightness(
    final ErrorDiffusionDitherBuilder.Algorithm algorithm,
    final ErrorDiffusionDither dither,
    final int[] pixels
  ) {
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, WIDTH, HEIGHT)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      final double ratio = DitherTestImages.whiteRatio(indices);
      int leftWhite = 0;
      int rightWhite = 0;
      for (int row = 0; row < HEIGHT; row++) {
        leftWhite += indices[row * WIDTH];
        rightWhite += indices[row * WIDTH + WIDTH - 1];
      }

      assertEquals(pixels.length, indices.length);

      final boolean balanced = ratio > 0.45 && ratio < 0.55;
      assertTrue(balanced, algorithm + " ratio " + ratio);

      final boolean leftMostlyBlack = leftWhite < HEIGHT / 4;
      assertTrue(leftMostlyBlack, algorithm + " left edge");

      final boolean rightMostlyWhite = rightWhite > (HEIGHT * 3) / 4;
      assertTrue(rightMostlyWhite, algorithm + " right edge");
    }
  }

  @Test
  void shouldWriteOnlyPaletteColorsWhenDitheringInPlace() {
    final int[] pixels = DitherTestImages.gradient(64, 16);
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    dither.dither(pixels, 64);
    for (final int pixel : pixels) {
      final boolean paletteColor = pixel == BLACK || pixel == WHITE;
      final String hex = Integer.toHexString(pixel);
      assertTrue(paletteColor, hex);
    }
  }

  @Test
  void shouldDiffuseErrorsOfSingleChannels() {
    final int[] pixels = { 0xFF006400, 0xFF000064, 0xFF640000, 0xFF000000 };
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      for (final byte index : indices) {
        assertEquals(0, index, "dark single-channel colors are closest to black");
      }
    }
  }

  @Test
  void shouldMapLonePrimaryPixelsToBlack() {
    final int[] colors = { 0xFF640000, 0xFF006400, 0xFF000064 };
    for (final int color : colors) {
      final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
      final int[] pixels = { color };
      try (final ImageBuffer image = ImageBuffer.buffer(pixels, 1, 1)) {
        final byte[] indices = dither.ditherIntoBytes(image);
        final String hex = Integer.toHexString(color);
        assertArrayEquals(new byte[] { 0 }, indices, hex);
      }
    }
  }

  @Test
  void shouldCreateTheRequestedAlgorithmFromTheBuilder() {
    for (final Map.Entry<ErrorDiffusionDitherBuilder.Algorithm, DiffusionKernel> entry : KERNELS.entrySet()) {
      final ErrorDiffusionDitherBuilder.Algorithm algorithm = entry.getKey();
      final DiffusionKernel expectedKernel = entry.getValue();
      final ErrorDiffusionDither dither = build(algorithm);
      final DiffusionKernel kernel = dither.getKernel();
      final DitherPalette palette = dither.getPalette();
      final String name = algorithm.name();
      assertSame(expectedKernel, kernel, name);
      assertSame(DitherTestImages.BLACK_WHITE, palette);
    }
  }

  @Test
  void shouldReturnTheBuilderFromBuilderMethodsForChaining() {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder withAlgorithm = builder.withAlgorithm(ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG);
    final ErrorDiffusionDitherBuilder withTemporalThreshold = builder.withTemporalThreshold(TEMPORAL_THRESHOLD);
    final ErrorDiffusionDitherBuilder withErrorThreshold = builder.withErrorThreshold(ERROR_THRESHOLD);
    final ErrorDiffusionDitherBuilder withErrorStrength = builder.withErrorStrength(ERROR_STRENGTH);
    final ErrorDiffusionDitherBuilder withPalette = builder.withPalette(DitherPalette.DEFAULT_MAP_PALETTE);
    assertSame(builder, withAlgorithm);
    assertSame(builder, withTemporalThreshold);
    assertSame(builder, withErrorThreshold);
    assertSame(builder, withErrorStrength);
    assertSame(builder, withPalette);
  }

  @Test
  void shouldAcceptTheBoundaryValuesOfEveryTemporalSettingInTheBuilder() {
    final ErrorDiffusionDitherBuilder lowest = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder lowestTemporal = lowest.withAlgorithm(ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG);
    final ErrorDiffusionDitherBuilder noThreshold = lowestTemporal.withTemporalThreshold(0);
    final ErrorDiffusionDitherBuilder noError = noThreshold.withErrorThreshold(0);
    final ErrorDiffusionDitherBuilder noStrength = noError.withErrorStrength(0.0f);
    final ErrorDiffusionDither lowestBuilt = noStrength.build();
    final TemporalDitherAlgorithm lowestTemporalDither = assertInstanceOf(TemporalDitherAlgorithm.class, lowestBuilt);
    final int lowestTemporalThreshold = lowestTemporalDither.getTemporalThreshold();
    final int lowestErrorThreshold = lowestTemporalDither.getErrorThreshold();
    final float lowestErrorStrength = lowestTemporalDither.getErrorStrength();
    assertEquals(0, lowestTemporalThreshold, "a temporal threshold of zero is the lowest allowed value");
    assertEquals(0, lowestErrorThreshold, "an error threshold of zero is the lowest allowed value");
    assertEquals(0.0f, lowestErrorStrength, "an error strength of zero is the lowest allowed value");

    final ErrorDiffusionDitherBuilder highest = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder highestTemporal = highest.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilder fullThreshold = highestTemporal.withTemporalThreshold(255);
    final ErrorDiffusionDitherBuilder fullStrength = fullThreshold.withErrorStrength(1.0f);
    final ErrorDiffusionDither highestBuilt = fullStrength.build();
    final TemporalDitherAlgorithm highestTemporalDither = assertInstanceOf(TemporalDitherAlgorithm.class, highestBuilt);
    final int highestTemporalThreshold = highestTemporalDither.getTemporalThreshold();
    final float highestErrorStrength = highestTemporalDither.getErrorStrength();
    assertEquals(255, highestTemporalThreshold, "a temporal threshold of 255 is the highest allowed value");
    assertEquals(1.0f, highestErrorStrength, "an error strength of one is the highest allowed value");
  }

  @Test
  void shouldPassTheTemporalSettingsFromTheBuilder() {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder temporalBuilder = builder.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilder stableBuilder = temporalBuilder.withTemporalThreshold(TEMPORAL_THRESHOLD);
    final ErrorDiffusionDitherBuilder quietBuilder = stableBuilder.withErrorThreshold(ERROR_THRESHOLD);
    final ErrorDiffusionDitherBuilder dampedBuilder = quietBuilder.withErrorStrength(ERROR_STRENGTH);
    final ErrorDiffusionDither built = dampedBuilder.build();
    final TemporalDitherAlgorithm temporal = assertInstanceOf(TemporalDitherAlgorithm.class, built);
    final int temporalThreshold = temporal.getTemporalThreshold();
    final int errorThreshold = temporal.getErrorThreshold();
    final float errorStrength = temporal.getErrorStrength();
    final DitherPalette palette = temporal.getPalette();
    assertEquals(TEMPORAL_THRESHOLD, temporalThreshold);
    assertEquals(ERROR_THRESHOLD, errorThreshold);
    assertEquals(ERROR_STRENGTH, errorStrength);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  @Test
  void shouldRejectInvalidSettingsInTheBuilder() {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    assertThrows(IllegalArgumentException.class, () -> builder.withTemporalThreshold(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.withTemporalThreshold(256));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorThreshold(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorStrength(-0.1f));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorStrength(1.1f));
    assertThrows(NullPointerException.class, () -> builder.withAlgorithm(null));
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
  }

  @Test
  void shouldUseTheMapPaletteInTheDefaultConstructors() {
    final ErrorDiffusionDither[] dithers = {
      new AtkinsonDither(),
      new BurkesDither(),
      new FilterLiteDither(),
      new FloydDither(),
      new JarvisJudiceNinkeDither(),
      new StevensonArceDither(),
      new StuckiDither(),
    };
    for (final ErrorDiffusionDither dither : dithers) {
      final DitherPalette palette = dither.getPalette();
      final Class<? extends ErrorDiffusionDither> type = dither.getClass();
      final String name = type.getSimpleName();
      assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette, name);
    }
  }

  @Test
  void shouldCreateTheCommonAlgorithmsFromTheFactories() {
    final ErrorDiffusionDither filterLite = DitherAlgorithm.filterLite();
    final ErrorDiffusionDither floydSteinberg = DitherAlgorithm.floydSteinberg();
    final TemporalDitherAlgorithm temporal = DitherAlgorithm.temporalFloydSteinberg();
    final TemporalDitherAlgorithm customTemporal = DitherAlgorithm.temporalFloydSteinberg(DitherTestImages.BLACK_WHITE, 1, 2, 0.25f);
    final DiffusionKernel filterLiteKernel = filterLite.getKernel();
    final DiffusionKernel floydKernel = floydSteinberg.getKernel();
    final int temporalThreshold = temporal.getTemporalThreshold();
    final int customErrorThreshold = customTemporal.getErrorThreshold();
    assertSame(DiffusionKernel.FILTER_LITE, filterLiteKernel);
    assertSame(DiffusionKernel.FLOYD_STEINBERG, floydKernel);
    assertEquals(TemporalDitherAlgorithm.DEFAULT_TEMPORAL_THRESHOLD, temporalThreshold);
    assertEquals(2, customErrorThreshold);
  }

  @ParameterizedTest
  @EnumSource(ErrorDiffusionDitherBuilder.Algorithm.class)
  void shouldDoNothingForEmptyInputOfEveryPositiveWidth(final ErrorDiffusionDitherBuilder.Algorithm algorithm) {
    final ErrorDiffusionDither dither = build(algorithm);
    final int[] empty = new int[0];
    assertThrows(IllegalArgumentException.class, () -> dither.dither(empty, 0));
    assertThrows(IllegalArgumentException.class, () -> dither.dither(empty, -1));
    assertDoesNotThrow(() -> dither.dither(empty, 1));
    assertDoesNotThrow(() -> dither.dither(empty, Integer.MAX_VALUE));
  }

  @Test
  void shouldRejectInvalidInput() {
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    final NullPointerException missingImage = assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null));
    final String missingImageMessage = missingImage.getMessage();
    assertEquals("Image must not be null!", missingImageMessage);
    assertThrows(NullPointerException.class, () -> dither.dither(null, 1));
    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[4], 0));
    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[5], 2));
    assertThrows(NullPointerException.class, () -> new FloydDither(null));
  }

  @Test
  void shouldSpreadTheErrorLikeTheTextbookWithFloydSteinberg() {
    final int middle = gray(MIDDLE_GRAY_LEVEL);
    final int[] pixels = { middle, middle, middle, middle };
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 4, 1)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 1, 0, 1, 0 }, indices);
    }
  }

  @Test
  void shouldScanOddRowsFromRightToLeft() {
    final int dark = gray(40);
    final int medium = gray(90);
    final int middle = gray(MIDDLE_GRAY_LEVEL);
    final int[] pixels = { dark, dark, dark, dark, medium, middle };
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 3, 2)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 1 }, indices);
    }
  }
}
