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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.StringReader;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.OrderedDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class PaletteTest {

  private static final String PALETTE_RESOURCE = "mcav/common/palette.json";
  private static final String MISSING_PALETTE_RESOURCE = "no-such-palette.json";

  private static final int RED = 0xFF0000;
  private static final int BLUE = 0x0000FF;
  private static final int OPAQUE_BLACK = 0xFF000000;

  private static final int LOOKUP_LEVEL = 200;

  private static final int MAXIMUM_PALETTE_SIZE = 256;

  private static final int SAMPLE_INDEX = 4;

  private static int[] parse(final String json) {
    final StringReader reader = new StringReader(json);
    return MapPaletteLoader.parseColors(reader);
  }

  @Test
  void shouldNeverOverwriteTheFirstEntryOfAnOddGreenRowInTheBlueLookup() {
    final DitherPalette palette = DitherPalette.colors(0x000000, BLUE);
    final byte[] lookup = palette.getColorMap();
    final int[] colors = palette.getFullColorMap();
    final byte lookupEntry = lookup[128];
    assertEquals(0, lookupEntry);

    final int color = colors[128];
    assertEquals(OPAQUE_BLACK, color);
  }

  @Test
  void shouldCountOnlyUsablePaletteColorsInTheOrderedSpread() {
    final int[] colors = { 0, 0, OPAQUE_BLACK, 0xFFFFFFFF };
    final DitherPalette palette = new ColorPalette(colors, 2);
    final PixelMapper mapper = PixelMapper.ofPixelMapper(BayerDither.NORMAL_2X2, 1.0f);
    final OrderedDither dither = new OrderedDither(palette, mapper);
    final int[] pixels = { 0xFF6A6A6A, 0xFF6A6A6A, 0xFF6A6A6A, 0xFF6A6A6A };
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2)) {
      final byte[] actual = dither.ditherIntoBytes(image);
      final byte[] expected = new byte[] { 2, 3, 3, 2 };
      assertArrayEquals(expected, actual);
    }
  }

  @Test
  void shouldMakeCustomPalettesOpaqueAndMapEveryColorToTheClosestEntry() {
    final DitherPalette palette = DitherPalette.colors(RED, BLUE);
    final int[] colors = palette.getPalette();
    final int size = palette.getSize();
    final int reserved = palette.getReservedIndices();
    final byte[] colorMap = palette.getColorMap();
    final int[] fullColorMap = palette.getFullColorMap();
    final int[] expectedColors = new int[] { 0xFFFF0000, 0xFF0000FF };
    assertArrayEquals(expectedColors, colors);
    assertEquals(2, size);
    assertEquals(0, reserved);

    for (int index = 0; index < colorMap.length; index += 997) {
      final int entry = colorMap[index];
      final int fullColor = fullColorMap[index];
      final int expectedFullColor = colors[entry];
      assertEquals(expectedFullColor, fullColor);
    }

    final int reddish = (100 << 14) | (5 << 7) | 5;
    final int bluish = (5 << 14) | (5 << 7) | 100;
    final byte reddishEntry = colorMap[reddish];
    assertEquals(0, reddishEntry);

    final byte bluishEntry = colorMap[bluish];
    assertEquals(1, bluishEntry);
  }

  @Test
  void shouldLookUpColorsByTheirFullRedChannel() {
    final DitherPalette palette = DitherPalette.colors(0x800000, RED);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(LOOKUP_LEVEL, 0, 0);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a red of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void shouldWeighTheChannelsByTheRedmeanFormula() {
    final float red = ColorPalette.perceptualDistance(0, 0, 0, 0x3C0000);
    final float green = ColorPalette.perceptualDistance(0, 0, 0, 0x003C00);
    final float blue = ColorPalette.perceptualDistance(0, 0, 0, 0x00003C);
    final float mixed = ColorPalette.perceptualDistance(100, 80, 60, 0x1428C8);
    assertEquals(7621.875f, red);
    assertEquals(14400.0f, green);
    assertEquals(10785.9375f, blue);
    assertEquals(74829.69f, mixed);

    final boolean redBelowBlue = red < blue;
    assertTrue(redBelowBlue, "the eye is least sensitive to red in a dark color");

    final boolean blueBelowGreen = blue < green;
    assertTrue(blueBelowGreen, "the eye is most sensitive to green");
  }

  @Test
  void shouldLookUpColorsByTheirFullGreenChannel() {
    final DitherPalette palette = DitherPalette.colors(0x008000, 0x00FF00);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0, LOOKUP_LEVEL, 0);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a green of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void shouldLookUpColorsByTheirFullBlueChannel() {
    final DitherPalette palette = DitherPalette.colors(0x000080, BLUE);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0, 0, LOOKUP_LEVEL);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a blue of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void shouldPreferTheFirstOfTwoEquallyCloseColors() {
    final DitherPalette palette = DitherPalette.colors(0x123456, 0x123456);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0x12, 0x34, 0x56);
    final int index = colorMap[lookup];
    assertEquals(0, index, "the first of two colors of the same distance wins");
  }

  @Test
  void shouldAcceptAPaletteOfTheLargestAllowedSize() {
    final int[] largest = new int[MAXIMUM_PALETTE_SIZE];
    for (int index = 0; index < largest.length; index++) {
      largest[index] = index;
    }

    final DitherPalette palette = DitherPalette.colors(largest);
    final int size = palette.getSize();
    assertEquals(MAXIMUM_PALETTE_SIZE, size, "a palette of exactly 256 colors is allowed");
  }

  @Test
  void shouldRejectInvalidPalettes() {
    final int[] tooMany = new int[MAXIMUM_PALETTE_SIZE + 1];
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors(tooMany));
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors());
    assertThrows(IllegalArgumentException.class, () -> new ColorPalette(new int[] { 1 }, -1));
    assertThrows(IllegalArgumentException.class, () -> new ColorPalette(new int[] { 1, 2 }, 2));
    assertThrows(NullPointerException.class, () -> DitherPalette.colors((int[]) null));
  }

  @Test
  void shouldKeepTheTransparentEntriesOfTheMapPaletteOutOfTheLookup() {
    final DitherPalette palette = DitherPalette.DEFAULT_MAP_PALETTE;
    final int reserved = palette.getReservedIndices();
    final int size = palette.getSize();
    final int[] colors = palette.getPalette();
    final byte[] colorMap = palette.getColorMap();
    final int colorCount = MapPaletteLoader.getColorCount();
    DitherPalette.init();
    assertEquals(MapPalette.TRANSPARENT_INDICES, reserved);
    assertEquals(colorCount, size);

    for (int index = 0; index < MapPalette.TRANSPARENT_INDICES; index++) {
      final int reservedColor = colors[index];
      assertEquals(0, reservedColor, "reserved entries are left empty");
    }

    for (final byte entry : colorMap) {
      final int unsigned = entry & 0xFF;
      final boolean opaque = unsigned >= MapPalette.TRANSPARENT_INDICES;
      assertTrue(opaque, "transparent entries must never be chosen");
    }

    final int eightBitSize = DitherPalette.EIGHT_BIT_PALETTE.getSize();
    assertEquals(8, eightBitSize);
  }

  @Test
  void shouldHoldEightColorsInTheEightBitPalette() {
    final DitherPalette palette = DitherPalette.EIGHT_BIT_PALETTE;
    final int[] colors = palette.getPalette();
    final int[] redGreenBlueWhiteBlackYellowCyanPurple = {
      0xFFFF0000,
      0xFF00D93A,
      0xFF3737DC,
      0xFFFFFFFF,
      0xFF000000,
      0xFFE5E533,
      0xFF5CDBD5,
      0xFFB24CD8,
    };
    assertArrayEquals(redGreenBlueWhiteBlackYellowCyanPurple, colors);
  }

  @Test
  void shouldHandOutCopiesFromTheLoader() {
    final int[] first = MapPaletteLoader.getColors();
    final int[] second = MapPaletteLoader.getColors();
    final int count = MapPaletteLoader.getColorCount();
    first[SAMPLE_INDEX] = 0;
    final int[] third = MapPaletteLoader.getColors();
    assertNotSame(first, second);
    assertEquals(count, second.length);

    final int thirdColor = third[SAMPLE_INDEX];
    final int expected = second[SAMPLE_INDEX];
    assertEquals(expected, thirdColor, "changing a copy must not change the palette");
  }

  @Test
  void shouldLoadTheBundledPaletteFromItsResource() {
    final int[] loaded = MapPaletteLoader.loadColors(PALETTE_RESOURCE);

    final int[] colors = MapPaletteLoader.getColors();
    assertArrayEquals(colors, loaded);
  }

  @Test
  void shouldProvideColorObjectsFromTheLoader() {
    final int[] colors = MapPaletteLoader.getColors();
    final Color color = MapPaletteLoader.getColor((byte) SAMPLE_INDEX);
    final int argb = color.getRGB();
    final int alpha = color.getAlpha();
    final int count = MapPaletteLoader.getColorCount();
    final byte missing = (byte) count;
    final int expected = colors[SAMPLE_INDEX];
    assertEquals(expected, argb);
    assertEquals(255, alpha);
    assertThrows(IllegalArgumentException.class, () -> MapPaletteLoader.getColor(missing));
  }

  @Test
  void shouldParseColorsFromJson() {
    final int[] colors = parse("[[1, 2, 3], [255, 128, 0]]");
    final int[] expected = new int[] { 0xFF010203, 0xFFFF8000 };
    assertArrayEquals(expected, colors);
  }

  @Test
  void shouldRejectJsonThatIsNotAPalette() {
    assertThrows(PaletteLoadingException.class, () -> parse("null"));
    assertThrows(PaletteLoadingException.class, () -> parse("[]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[1, 2]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[null]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[256, 0, 0]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[0, -1, 0]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[0, 0, 300]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("{\"not\": \"an array\"}"));
    assertThrows(PaletteLoadingException.class, () -> MapPaletteLoader.loadColors(MISSING_PALETTE_RESOURCE));
  }

  // Gson reads the empty element as a null, which an int[] cannot hold, and throws an IllegalArgumentException, not a syntax error.
  @Test
  void shouldRejectAColorWithAnEmptyComponent() {
    assertThrows(PaletteLoadingException.class, () -> parse("[[0,,1]]"));
  }

  @Test
  void shouldNotInstantiateTheLoader() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MapPaletteLoader.class);
  }
}
