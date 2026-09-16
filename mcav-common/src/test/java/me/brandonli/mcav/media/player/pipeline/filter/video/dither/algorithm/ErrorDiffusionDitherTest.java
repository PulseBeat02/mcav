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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilderImpl;
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

/**
 * Tests the error diffusion algorithms, their builder and the factories of {@link DitherAlgorithm}.
 */
final class ErrorDiffusionDitherTest {

  private static final int WIDTH = 256;
  private static final int HEIGHT = 64;

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
    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilderImpl withAlgorithm = builder.withAlgorithm(algorithm);
    final ErrorDiffusionDitherBuilderImpl configured = withAlgorithm.withPalette(DitherTestImages.BLACK_WHITE);
    return configured.build();
  }

  private static int gray(final int value) {
    return 0xFF000000 | (value << 16) | (value << 8) | value;
  }

  @Test
  void everyAlgorithmPreservesTheAverageBrightness() {
    final int[] pixels = DitherTestImages.gradient(WIDTH, HEIGHT);
    for (final ErrorDiffusionDitherBuilder.Algorithm algorithm : ErrorDiffusionDitherBuilder.Algorithm.values()) {
      final ErrorDiffusionDither dither = build(algorithm);
      try (final ImageBuffer image = ImageBuffer.buffer(pixels, WIDTH, HEIGHT)) {
        final byte[] indices = dither.ditherIntoBytes(image);
        final double ratio = DitherTestImages.whiteRatio(indices);
        int leftWhite = 0;
        int rightWhite = 0;
        for (int y = 0; y < HEIGHT; y++) {
          leftWhite += indices[y * WIDTH];
          rightWhite += indices[y * WIDTH + WIDTH - 1];
        }
        assertEquals(pixels.length, indices.length);
        assertTrue(ratio > 0.45 && ratio < 0.55, algorithm + " ratio " + ratio);
        assertTrue(leftWhite < HEIGHT / 4, algorithm + " left edge");
        assertTrue(rightWhite > (HEIGHT * 3) / 4, algorithm + " right edge");
      }
    }
  }

  @Test
  void ditherInPlaceWritesOnlyPaletteColors() {
    final int[] pixels = DitherTestImages.gradient(64, 16);
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    dither.dither(pixels, 64);
    for (final int pixel : pixels) {
      final boolean paletteColor = pixel == 0xFF000000 || pixel == 0xFFFFFFFF;
      final String hex = Integer.toHexString(pixel);
      assertTrue(paletteColor, hex);
    }
  }

  @Test
  void diffusesErrorsOfSingleChannels() {
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
  void mapsLonePrimaryPixelsToBlack() {
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
  void builderCreatesTheRequestedAlgorithm() {
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
  void builderMethodsReturnTheBuilderForChaining() {
    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilderImpl withAlgorithm = builder.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilderImpl withTemporalThreshold = builder.withTemporalThreshold(20);
    final ErrorDiffusionDitherBuilderImpl withErrorThreshold = builder.withErrorThreshold(7);
    final ErrorDiffusionDitherBuilderImpl withErrorStrength = builder.withErrorStrength(0.5f);
    final ErrorDiffusionDitherBuilderImpl withPalette = builder.withPalette(DitherPalette.DEFAULT_MAP_PALETTE);
    assertSame(builder, withAlgorithm);
    assertSame(builder, withTemporalThreshold);
    assertSame(builder, withErrorThreshold);
    assertSame(builder, withErrorStrength);
    assertSame(builder, withPalette);
  }

  @Test
  void builderAcceptsTheBoundaryValuesOfEveryTemporalSetting() {
    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> lowest = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilderImpl lowestTemporal = lowest.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilderImpl noThreshold = lowestTemporal.withTemporalThreshold(0);
    final ErrorDiffusionDitherBuilderImpl noError = noThreshold.withErrorThreshold(0);
    final ErrorDiffusionDitherBuilderImpl noStrength = noError.withErrorStrength(0.0f);
    final ErrorDiffusionDither lowestBuilt = noStrength.build();
    final TemporalDitherAlgorithm lowestTemporalDither = assertInstanceOf(TemporalDitherAlgorithm.class, lowestBuilt);
    final int lowestTemporalThreshold = lowestTemporalDither.getTemporalThreshold();
    final int lowestErrorThreshold = lowestTemporalDither.getErrorThreshold();
    final float lowestErrorStrength = lowestTemporalDither.getErrorStrength();
    assertEquals(0, lowestTemporalThreshold, "a temporal threshold of zero is the lowest allowed value");
    assertEquals(0, lowestErrorThreshold, "an error threshold of zero is the lowest allowed value");
    assertEquals(0.0f, lowestErrorStrength, "an error strength of zero is the lowest allowed value");

    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> highest = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilderImpl highestTemporal = highest.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilderImpl fullThreshold = highestTemporal.withTemporalThreshold(255);
    final ErrorDiffusionDitherBuilderImpl fullStrength = fullThreshold.withErrorStrength(1.0f);
    final ErrorDiffusionDither highestBuilt = fullStrength.build();
    final TemporalDitherAlgorithm highestTemporalDither = assertInstanceOf(TemporalDitherAlgorithm.class, highestBuilt);
    final int highestTemporalThreshold = highestTemporalDither.getTemporalThreshold();
    final float highestErrorStrength = highestTemporalDither.getErrorStrength();
    assertEquals(255, highestTemporalThreshold, "a temporal threshold of 255 is the highest allowed value");
    assertEquals(1.0f, highestErrorStrength, "an error strength of one is the highest allowed value");
  }

  @Test
  void builderPassesTheTemporalSettings() {
    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilderImpl temporalBuilder = builder.withAlgorithm(
      ErrorDiffusionDitherBuilder.Algorithm.TEMPORAL_FLOYD_STEINBERG
    );
    final ErrorDiffusionDitherBuilderImpl stableBuilder = temporalBuilder.withTemporalThreshold(20);
    final ErrorDiffusionDitherBuilderImpl quietBuilder = stableBuilder.withErrorThreshold(7);
    final ErrorDiffusionDitherBuilderImpl dampedBuilder = quietBuilder.withErrorStrength(0.5f);
    final ErrorDiffusionDither built = dampedBuilder.build();
    final TemporalDitherAlgorithm temporal = assertInstanceOf(TemporalDitherAlgorithm.class, built);
    final int temporalThreshold = temporal.getTemporalThreshold();
    final int errorThreshold = temporal.getErrorThreshold();
    final float errorStrength = temporal.getErrorStrength();
    final DitherPalette palette = temporal.getPalette();
    assertEquals(20, temporalThreshold);
    assertEquals(7, errorThreshold);
    assertEquals(0.5f, errorStrength);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  @Test
  void builderRejectsInvalidSettings() {
    final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> builder = DitherAlgorithm.errorDiffusion();
    assertThrows(IllegalArgumentException.class, () -> builder.withTemporalThreshold(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.withTemporalThreshold(256));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorThreshold(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorStrength(-0.1f));
    assertThrows(IllegalArgumentException.class, () -> builder.withErrorStrength(1.1f));
    assertThrows(NullPointerException.class, () -> builder.withAlgorithm(null));
    assertThrows(NullPointerException.class, () -> builder.withPalette(null));
  }

  @Test
  void defaultConstructorsUseTheMapPalette() {
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
  void factoriesCreateTheCommonAlgorithms() {
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

  @Test
  void rejectsInvalidInput() {
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    assertThrows(NullPointerException.class, () -> dither.ditherIntoBytes(null));
    assertThrows(NullPointerException.class, () -> dither.dither(null, 1));
    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[4], 0));
    assertThrows(IllegalArgumentException.class, () -> dither.dither(new int[5], 2));
    assertThrows(NullPointerException.class, () -> new FloydDither(null));
  }

  @Test
  void floydSteinbergSpreadsTheErrorLikeTheTextbook() {
    final int middle = gray(128);
    final int[] pixels = { middle, middle, middle, middle };
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 4, 1)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      // 128 becomes white with an error of -127; 128 - 7/16 * 127 = 73 becomes black with an error of 73;
      // 128 + 7/16 * 73 = 159 becomes white with an error of -96; 128 - 7/16 * 96 = 86 becomes black
      assertArrayEquals(new byte[] { 1, 0, 1, 0 }, indices);
    }
  }

  @Test
  void scansOddRowsFromRightToLeft() {
    final int dark = gray(40);
    final int medium = gray(90);
    final int middle = gray(128);
    final int[] pixels = { dark, dark, dark, dark, medium, middle };
    final ErrorDiffusionDither dither = build(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 3, 2)) {
      final byte[] indices = dither.ditherIntoBytes(image);
      // the black first row leaves 22, 31 and 23 for the second row, which starts on the right: 128 + 23 = 151
      // becomes white with an error of -104, so the middle pixel gets 90 + 31 - 45 = 76 and stays black; scanning
      // the second row from the left would make the middle pixel white instead
      assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 1 }, indices);
    }
  }
}
