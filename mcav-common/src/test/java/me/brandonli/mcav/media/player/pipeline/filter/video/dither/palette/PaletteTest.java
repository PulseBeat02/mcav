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
import java.util.List;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DitherPalette}, {@link ColorPalette}, {@link MapPalette} and {@link MapPaletteLoader}.
 */
final class PaletteTest {

  private static int[] parse(final String json) {
    final StringReader reader = new StringReader(json);
    return MapPaletteLoader.parseColors(reader);
  }

  @Test
  void customPalettesAreOpaqueAndMapEveryColorToTheClosestEntry() {
    final DitherPalette palette = DitherPalette.colors(0xFF0000, 0x0000FF);
    final int[] colors = palette.getPalette();
    final int size = palette.getSize();
    final int reserved = palette.getReservedIndices();
    final byte[] colorMap = palette.getColorMap();
    final int[] fullColorMap = palette.getFullColorMap();
    assertArrayEquals(new int[] { 0xFFFF0000, 0xFF0000FF }, colors);
    assertEquals(2, size);
    assertEquals(0, reserved);
    for (int index = 0; index < colorMap.length; index += 997) {
      final int entry = colorMap[index];
      assertEquals(colors[entry], fullColorMap[index]);
    }
    final int reddish = (100 << 14) | (5 << 7) | 5;
    final int bluish = (5 << 14) | (5 << 7) | 100;
    assertEquals(0, colorMap[reddish]);
    assertEquals(1, colorMap[bluish]);
  }

  @Test
  void looksUpColorsByTheirFullRedChannel() {
    final DitherPalette palette = DitherPalette.colors(0x800000, 0xFF0000);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(200, 0, 0);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a red of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void weighsTheChannelsByTheRedmeanFormula() {
    // black against (60, 0, 0): the red mean is 30, so red weighs 2 + 30 / 256 = 2.1171875, times 60 squared
    final float red = ColorPalette.perceptualDistance(0, 0, 0, 0x3C0000);
    // green always weighs 4, times 60 squared
    final float green = ColorPalette.perceptualDistance(0, 0, 0, 0x003C00);
    // the red mean is 0, so blue weighs 2 + 255 / 256 = 2.99609375, times 60 squared
    final float blue = ColorPalette.perceptualDistance(0, 0, 0, 0x00003C);
    // (100, 80, 60) against (20, 40, 200): the red mean is 60, so red weighs 2.234375 times 80 squared = 14300,
    // green 4 times 40 squared = 6400, and blue 2 + 195 / 256 = 2.76171875 times 140 squared = 54129.6875
    final float mixed = ColorPalette.perceptualDistance(100, 80, 60, 0x1428C8);
    assertEquals(7621.875f, red);
    assertEquals(14400.0f, green);
    assertEquals(10785.9375f, blue);
    // 74829.6875 is exactly representable, and 74829.69 is how a float of that value prints
    assertEquals(74829.69f, mixed);
    assertTrue(red < blue, "the eye is least sensitive to red in a dark color");
    assertTrue(blue < green, "the eye is most sensitive to green");
  }

  @Test
  void looksUpColorsByTheirFullGreenChannel() {
    final DitherPalette palette = DitherPalette.colors(0x008000, 0x00FF00);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0, 200, 0);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a green of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void looksUpColorsByTheirFullBlueChannel() {
    final DitherPalette palette = DitherPalette.colors(0x000080, 0x0000FF);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0, 0, 200);
    final int index = colorMap[lookup];
    assertEquals(1, index, "a blue of 200 is closer to 255 than to 128, which only holds if the level is doubled");
  }

  @Test
  void prefersTheFirstOfTwoEquallyCloseColors() {
    final DitherPalette palette = DitherPalette.colors(0x123456, 0x123456);
    final byte[] colorMap = palette.getColorMap();
    final int lookup = DitherUtils.getLookupIndex(0x12, 0x34, 0x56);
    final int index = colorMap[lookup];
    assertEquals(0, index, "the first of two colors of the same distance wins");
  }

  @Test
  void acceptsAPaletteOfTheLargestAllowedSize() {
    final int[] largest = new int[256];
    for (int index = 0; index < largest.length; index++) {
      largest[index] = index;
    }
    final DitherPalette palette = DitherPalette.colors(largest);
    final int size = palette.getSize();
    assertEquals(256, size, "a palette of exactly 256 colors is allowed");
  }

  @Test
  void acceptsColorLists() {
    final DitherPalette fromList = DitherPalette.colors(List.of(0x000000, 0xFFFFFF));
    final DitherPalette fromArray = DitherPalette.colors(0x000000, 0xFFFFFF);
    final int[] listColors = fromList.getPalette();
    final int[] arrayColors = fromArray.getPalette();
    assertArrayEquals(arrayColors, listColors);
  }

  @Test
  void rejectsInvalidPalettes() {
    final int[] tooMany = new int[257];
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors(tooMany));
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors());
    assertThrows(IllegalArgumentException.class, () -> new ColorPalette(new int[] { 1 }, -1));
    assertThrows(IllegalArgumentException.class, () -> new ColorPalette(new int[] { 1, 2 }, 2));
    assertThrows(NullPointerException.class, () -> DitherPalette.colors((int[]) null));
    assertThrows(NullPointerException.class, () -> DitherPalette.colors((List<Integer>) null));
  }

  @Test
  void theMapPaletteKeepsItsTransparentEntriesOutOfTheLookup() {
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
      assertEquals(0, colors[index], "reserved entries are left empty");
    }
    for (final byte entry : colorMap) {
      final int unsigned = entry & 0xFF;
      assertTrue(unsigned >= MapPalette.TRANSPARENT_INDICES, "transparent entries must never be chosen");
    }
    final int eightBitSize = DitherPalette.EIGHT_BIT_PALETTE.getSize();
    assertEquals(8, eightBitSize);
  }

  @Test
  void theEightBitPaletteHoldsItsEightColors() {
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
  void loaderHandsOutCopies() {
    final int[] first = MapPaletteLoader.getColors();
    final int[] second = MapPaletteLoader.getColors();
    final int count = MapPaletteLoader.getColorCount();
    first[4] = 0;
    final int[] third = MapPaletteLoader.getColors();
    assertNotSame(first, second);
    assertEquals(count, second.length);
    assertEquals(second[4], third[4], "changing a copy must not change the palette");
  }

  @Test
  void loaderProvidesColorObjects() {
    final int[] colors = MapPaletteLoader.getColors();
    final Color color = MapPaletteLoader.getColor((byte) 4);
    final int argb = color.getRGB();
    final int alpha = color.getAlpha();
    final int count = MapPaletteLoader.getColorCount();
    final byte missing = (byte) count;
    assertEquals(colors[4], argb);
    assertEquals(255, alpha);
    assertThrows(IllegalArgumentException.class, () -> MapPaletteLoader.getColor(missing));
  }

  @Test
  void parsesColorsFromJson() {
    final int[] colors = parse("[[1, 2, 3], [255, 128, 0]]");
    assertArrayEquals(new int[] { 0xFF010203, 0xFFFF8000 }, colors);
  }

  @Test
  void rejectsJsonThatIsNotAPalette() {
    assertThrows(PaletteLoadingException.class, () -> parse("null"));
    assertThrows(PaletteLoadingException.class, () -> parse("[]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[1, 2]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[null]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[256, 0, 0]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[0, -1, 0]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("[[0, 0, 300]]"));
    assertThrows(PaletteLoadingException.class, () -> parse("{\"not\": \"an array\"}"));
    assertThrows(PaletteLoadingException.class, () -> MapPaletteLoader.loadColors("no-such-palette.json"));
  }

  @Test
  void loaderCannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MapPaletteLoader.class);
  }
}
