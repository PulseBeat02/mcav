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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.AtkinsonDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.BurkesDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.DiffusionKernel;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FloydDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.JarvisJudiceNinkeDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StevensonArceDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.StuckiDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.TemporalFloydSteinbergDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest.ColorMapNearestDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.UniformNoiseDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.XoroshiroRandomProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;

final class DitherPropertyTest {

  private static final String SEED = "20260925";
  private static final int MAXIMUM_WIDTH = 48;
  private static final int MAXIMUM_HEIGHT = 32;

  private static final List<DitherPalette> PALETTES = List.of(
    DitherPalette.DEFAULT_MAP_PALETTE,
    DitherPalette.EIGHT_BIT_PALETTE,
    DitherTestImages.BLACK_WHITE,
    DitherPalette.colors(0xFF123456),
    DitherPalette.colors(0xFF808080, 0xFF808080, 0xFFFF0000)
  );

  // Every algorithm the library ships, created fresh for a palette; the random one draws from a seeded generator, so
  // two instances make the same choices.
  private static final List<NamedAlgorithm> ALGORITHMS = List.of(
    new NamedAlgorithm("Floyd-Steinberg", FloydDither::new),
    new NamedAlgorithm("Filter Lite", FilterLiteDither::new),
    new NamedAlgorithm("Atkinson", AtkinsonDither::new),
    new NamedAlgorithm("Burkes", BurkesDither::new),
    new NamedAlgorithm("Jarvis-Judice-Ninke", JarvisJudiceNinkeDither::new),
    new NamedAlgorithm("Stucki", StuckiDither::new),
    new NamedAlgorithm("Stevenson-Arce", StevensonArceDither::new),
    new NamedAlgorithm("temporal Floyd-Steinberg", TemporalFloydSteinbergDither::new),
    new NamedAlgorithm("nearest", ColorMapNearestDither::new),
    new NamedAlgorithm("ordered 8x8", DitherPropertyTest::createOrdered),
    new NamedAlgorithm("random", DitherPropertyTest::createRandom)
  );

  private static DitherAlgorithm createOrdered(final DitherPalette palette) {
    final PixelMapper mapper = PixelMapper.ofPixelMapper(
      BayerDither.NORMAL_8X8,
      BayerDither.NORMAL_8X8_MAXIMUM,
      PixelMapper.NORMAL_STRENGTH
    );
    return new OrderedDither(palette, mapper);
  }

  private static DitherAlgorithm createRandom(final DitherPalette palette) {
    final XoroshiroRandomProvider random = new XoroshiroRandomProvider(42L);
    return new UniformNoiseDither(palette, 64, random);
  }

  // Finds every kernel the library ships as a constant, so a kernel added later is checked as well.
  private static List<Field> kernelConstants() {
    final List<Field> kernels = new ArrayList<>();
    for (final Field field : DiffusionKernel.class.getFields()) {
      final int modifiers = field.getModifiers();
      final boolean constant = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);
      final Class<?> type = field.getType();
      if (constant && type == DiffusionKernel.class) {
        kernels.add(field);
      }
    }

    return kernels;
  }

  @Provide
  Arbitrary<Field> kernels() {
    final List<Field> constants = kernelConstants();
    return Arbitraries.of(constants);
  }

  @Property(seed = SEED, generation = GenerationMode.EXHAUSTIVE)
  void shouldSpreadTheWholeErrorWithEveryKernelExceptAtkinsonWhichSpreadsThreeQuarters(@ForAll("kernels") final Field constant)
    throws IllegalAccessException {
    final DiffusionKernel kernel = (DiffusionKernel) constant.get(null);
    final int divisor = kernel.getDivisor();
    long weights = 0;
    for (int tap = 0; tap < kernel.getTapCount(); tap++) {
      weights += kernel.getWeight(tap);
    }

    final boolean atkinson = kernel == DiffusionKernel.ATKINSON;
    final long expected = atkinson ? (divisor * 3L) / 4 : divisor;

    assertEquals(expected, weights, () -> "%s: weights against divisor %s".formatted(kernel, divisor));
  }

  @Provide
  Arbitrary<DitherCase> cases() {
    final Arbitrary<NamedAlgorithm> algorithms = Arbitraries.of(ALGORITHMS);
    final Arbitrary<DitherPalette> palettes = Arbitraries.of(PALETTES);
    final Arbitrary<Integer> widths = between(1, MAXIMUM_WIDTH);
    final Arbitrary<Integer> heights = between(1, MAXIMUM_HEIGHT);
    final IntegerArbitrary colors = Arbitraries.integers();
    final Arbitrary<Integer> extremes = Arbitraries.of(0, -1, 0xFF000000, 0x00FFFFFF, 0xFFFFFFFF, 0x80FF00FF);
    final Arbitrary<Integer> pixel = Arbitraries.oneOf(colors, extremes);
    final ListArbitrary<Integer> pixelList = pixel.list();
    final ListArbitrary<Integer> pixels = pixelList.ofSize(MAXIMUM_WIDTH * MAXIMUM_HEIGHT);
    final Combinators.Combinator5<NamedAlgorithm, DitherPalette, Integer, Integer, List<Integer>> cases = Combinators.combine(
      algorithms,
      palettes,
      widths,
      heights,
      pixels
    );
    return cases.as(DitherCase::new);
  }

  // Creates integers in a range from a fresh arbitrary: jqwik 1.10 shares the range between an arbitrary and the ones
  // configured from it, so one base configured twice would hand every user the last range.
  private static Arbitrary<Integer> between(final int minimum, final int maximum) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(minimum, maximum);
  }

  @Property(seed = SEED)
  void shouldTurnEveryPixelIntoAColorOfThePaletteThatTheIndexNames(@ForAll("cases") final DitherCase ditherCase) {
    final int[] image = ditherCase.createImage();
    final int[] mirrored = mirror(image, ditherCase.getWidth());

    assertDithersIntoThePalette(ditherCase, image);
    assertDithersIntoThePalette(ditherCase, mirrored);
  }

  private static void assertDithersIntoThePalette(final DitherCase ditherCase, final int[] image) {
    final DitherPalette palette = ditherCase.getPalette();
    final int width = ditherCase.getWidth();
    final int height = image.length / width;
    final DitherAlgorithm forColors = ditherCase.createAlgorithm();
    final DitherAlgorithm forIndices = ditherCase.createAlgorithm();
    final int[] colors = image.clone();
    forColors.dither(colors, width);
    final ImageBuffer buffer = createBuffer(image, width, height);
    final byte[] indices = forIndices.ditherIntoBytes(buffer);

    final int[] paletteColors = palette.getPalette();
    final int reserved = palette.getReservedIndices();
    final int size = palette.getSize();
    assertEquals(image.length, indices.length, "one index per pixel");

    for (int pixel = 0; pixel < image.length; pixel++) {
      final int index = indices[pixel] & 0xFF;
      final int position = pixel;
      final boolean usable = index >= reserved && index < size;
      assertTrue(usable, () -> "pixel %s got index %s, outside of %s..%s".formatted(position, index, reserved, size - 1));
      final int color = colors[pixel];
      final int named = paletteColors[index];
      assertEquals(named, color, () -> "pixel %s: the index names another color than dither wrote".formatted(position));
    }
  }

  private static ImageBuffer createBuffer(final int[] pixels, final int width, final int height) {
    final ImageBuffer buffer = mock(ImageBuffer.class);
    when(buffer.getWidth()).thenReturn(width);
    when(buffer.getHeight()).thenReturn(height);
    when(buffer.getPixelCount()).thenReturn(pixels.length);
    when(buffer.getPixels()).thenAnswer(_ -> pixels.clone());
    return buffer;
  }

  private static int[] mirror(final int[] image, final int width) {
    final int[] mirrored = new int[image.length];
    for (int pixel = 0; pixel < image.length; pixel++) {
      final int row = pixel / width;
      final int column = pixel % width;
      mirrored[row * width + width - 1 - column] = image[pixel];
    }

    return mirrored;
  }

  private static final class NamedAlgorithm {

    private final String name;
    private final Function<DitherPalette, DitherAlgorithm> factory;

    NamedAlgorithm(final String name, final Function<DitherPalette, DitherAlgorithm> factory) {
      this.name = name;
      this.factory = factory;
    }

    DitherAlgorithm create(final DitherPalette palette) {
      return this.factory.apply(palette);
    }

    @Override
    public String toString() {
      return this.name;
    }
  }

  private static final class DitherCase {

    private final NamedAlgorithm algorithm;
    private final DitherPalette palette;
    private final int width;
    private final int height;
    private final List<Integer> pixels;

    DitherCase(final NamedAlgorithm algorithm, final DitherPalette palette, final int width, final int height, final List<Integer> pixels) {
      this.algorithm = algorithm;
      this.palette = palette;
      this.width = width;
      this.height = height;
      this.pixels = pixels;
    }

    private DitherAlgorithm createAlgorithm() {
      return this.algorithm.create(this.palette);
    }

    private int[] createImage() {
      final int[] image = new int[this.width * this.height];
      for (int pixel = 0; pixel < image.length; pixel++) {
        image[pixel] = this.pixels.get(pixel);
      }

      return image;
    }

    private DitherPalette getPalette() {
      return this.palette;
    }

    int getWidth() {
      return this.width;
    }

    @Override
    public String toString() {
      final int paletteSize = this.palette.getSize();
      return "%s on %sx%s with a palette of %s colors".formatted(this.algorithm, this.width, this.height, paletteSize);
    }
  }
}
