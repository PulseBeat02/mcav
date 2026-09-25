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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.DiffusionKernel;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.ThresholdMatrix;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDitherImpl;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.XoroshiroRandomProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the dithering algorithms through the ways the library lets a developer shape one: an error diffusion kernel
 * of their own, a threshold matrix of their own with any level count and strength, a random weight and seed, and the
 * thresholds of temporal dithering. A constructor may refuse what it documents with an
 * {@link IllegalArgumentException}; an algorithm it accepted must dither any image into the palette, with every index
 * naming the color written in place.
 */
@Tag("fuzz")
final class DitherFuzzTest {

  private static final DitherPalette PALETTE = DitherPalette.EIGHT_BIT_PALETTE;
  private static final int MAX_SIDE = 24;

  @FuzzTest(maxDuration = "30s")
  void everyAcceptedAlgorithmDithersIntoThePalette(final FuzzedDataProvider data) {
    final int kind = data.consumeInt(0, 3);
    final DitherAlgorithm forColors;
    final DitherAlgorithm forIndices;
    try {
      final long seed = data.consumeLong();
      final AlgorithmRecipe recipe = new AlgorithmRecipe(kind, data);
      forColors = recipe.create(seed);
      forIndices = recipe.create(seed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    final int width = data.consumeInt(1, MAX_SIDE);
    final int height = data.consumeInt(1, MAX_SIDE);
    final int[] image = new int[width * height];
    for (int pixel = 0; pixel < image.length; pixel++) {
      image[pixel] = data.consumeInt();
    }

    final int[] colors = image.clone();
    forColors.dither(colors, width);
    final ImageBuffer buffer = createBuffer(image, width, height);
    final byte[] indices = forIndices.ditherIntoBytes(buffer);

    final int[] palette = PALETTE.getPalette();
    final int reserved = PALETTE.getReservedIndices();
    final int size = PALETTE.getSize();
    assertEquals(image.length, indices.length, "one index per pixel");
    for (int pixel = 0; pixel < image.length; pixel++) {
      final int index = indices[pixel] & 0xFF;
      final boolean usable = index >= reserved && index < size;
      assertTrue(usable, "an index outside of the palette");
      assertEquals(palette[index], colors[pixel], "the index names the color written in place");
    }
  }

  /**
   * An image buffer that only holds pixels, which is all the algorithms read.
   */
  private static ImageBuffer createBuffer(final int[] pixels, final int width, final int height) {
    final ClassLoader loader = ImageBuffer.class.getClassLoader();
    final Class<?>[] interfaces = { ImageBuffer.class };
    final InvocationHandler handler = (final Object proxy, final Method method, final Object[] arguments) ->
      switch (method.getName()) {
        case "getWidth" -> width;
        case "getHeight" -> height;
        case "getPixelCount" -> pixels.length;
        case "getPixels" -> pixels.clone();
        default -> throw new UnsupportedOperationException(method.getName());
      };
    return (ImageBuffer) Proxy.newProxyInstance(loader, interfaces, handler);
  }

  /**
   * The parts of an algorithm, read from the fuzzer's data once, so that two identical algorithms can be created.
   */
  private static final class AlgorithmRecipe {

    private final int kind;
    private final int[][] taps;
    private final int divisor;
    private final int[][] matrix;
    private final int levels;
    private final float strength;
    private final int weight;
    private final int temporalThreshold;
    private final int errorThreshold;

    AlgorithmRecipe(final int kind, final FuzzedDataProvider data) {
      this.kind = kind;
      final int tapCount = data.consumeInt(1, 12);
      this.taps = new int[tapCount][3];
      for (final int[] tap : this.taps) {
        tap[0] = data.consumeInt();
        tap[1] = data.consumeInt();
        tap[2] = data.consumeInt();
      }
      this.divisor = data.consumeInt();
      final int rows = data.consumeInt(1, 8);
      final int columns = data.consumeInt(1, 8);
      this.matrix = new int[rows][columns];
      for (final int[] row : this.matrix) {
        for (int column = 0; column < columns; column++) {
          row[column] = data.consumeInt();
        }
      }
      this.levels = data.consumeInt();
      this.strength = data.consumeFloat();
      this.weight = data.consumeInt(-8, 300);
      this.temporalThreshold = data.consumeInt();
      this.errorThreshold = data.consumeInt();
    }

    DitherAlgorithm create(final long seed) {
      return switch (this.kind) {
        case 0 -> {
          final DiffusionKernel kernel = new DiffusionKernel("fuzzed", this.divisor, this.taps);
          yield new ErrorDiffusionDither(PALETTE, kernel) {};
        }
        case 1 -> {
          final ThresholdMatrix thresholds = ThresholdMatrix.of(this.matrix);
          final PixelMapper mapper = PixelMapper.ofPixelMapper(thresholds, this.levels, this.strength);
          yield new OrderedDither(PALETTE, mapper);
        }
        case 2 -> {
          final XoroshiroRandomProvider random = new XoroshiroRandomProvider(seed);
          yield new RandomDitherImpl(PALETTE, this.weight, random);
        }
        default -> new TemporalFloydSteinbergDither(PALETTE, this.temporalThreshold, this.errorThreshold, this.strength);
      };
    }
  }
}
