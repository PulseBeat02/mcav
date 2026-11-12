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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class DitherUtilsTest {

  private static final DitherPalette BLACK_WHITE = DitherPalette.colors(0x000000, 0xFFFFFF);
  private static final DitherPalette BLACK_RED_GREEN = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00);

  private static final int OPAQUE_BLACK = 0xFF000000;
  private static final int OPAQUE_WHITE = 0xFFFFFFFF;
  private static final int TRANSPARENT_WHITE = 0x00FFFFFF;

  private static final int CHANNEL_MAXIMUM = 255;

  @Test
  void shouldDropTheLowestBitOfEveryChannelForTheLookup() {
    final int black = DitherUtils.getLookupIndex(0, 0, 0);
    final int white = DitherUtils.getLookupIndex(CHANNEL_MAXIMUM, CHANNEL_MAXIMUM, CHANNEL_MAXIMUM);
    final int mixed = DitherUtils.getLookupIndex(2, 4, 6);
    final int roundedDown = DitherUtils.getLookupIndex(3, 5, 7);
    final int expectedWhite = (127 << 14) | (127 << 7) | 127;
    final int expectedMixed = (1 << 14) | (2 << 7) | 3;
    assertEquals(0, black);
    assertEquals(expectedWhite, white);
    assertEquals(expectedMixed, mixed);
    assertEquals(mixed, roundedDown);
  }

  @Test
  void shouldFindTheClosestPaletteEntry() {
    final byte dark = DitherUtils.getBestColor(BLACK_WHITE, 10, 10, 10);
    final byte bright = DitherUtils.getBestColor(BLACK_WHITE, 250, 250, 250);
    final int darkColor = DitherUtils.getBestFullColor(BLACK_WHITE, 10, 10, 10);
    final int brightColor = DitherUtils.getBestColorNormal(BLACK_WHITE, 250, 250, 250);
    final int indexedColor = DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 1);
    assertEquals(0, dark);
    assertEquals(1, bright);
    assertEquals(OPAQUE_BLACK, darkColor);
    assertEquals(OPAQUE_WHITE, brightColor);
    assertEquals(OPAQUE_WHITE, indexedColor);
  }

  @Test
  void shouldRejectColorComponentsOutsideTheChannelRange() {
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColor(BLACK_WHITE, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColor(BLACK_WHITE, 0, 256, 0));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestFullColor(BLACK_WHITE, 0, 0, 256));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColorNormal(BLACK_WHITE, 0, 0, -1));
  }

  @Test
  void shouldRejectMissingPalettes() {
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestColor(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestFullColor(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestColorNormal(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getColorFromMinecraftPalette(null, (byte) 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestColorIncludingTransparent(null, OPAQUE_WHITE));
  }

  @Test
  void shouldRejectPaletteIndicesThePaletteDoesNotHave() {
    final int lastColor = DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 1);
    assertEquals(OPAQUE_WHITE, lastColor);
    assertThrows(IndexOutOfBoundsException.class, () -> DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 2));
    assertThrows(IndexOutOfBoundsException.class, () -> DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) -1));
  }

  @Test
  void shouldReadMapDataIndicesAsUnsignedAndGiveReservedIndicesNoColor() {
    final DitherPalette mapPalette = DitherPalette.DEFAULT_MAP_PALETTE;
    final int[] colors = mapPalette.getPalette();
    final int highIndex = 200;
    final int highColor = DitherUtils.getColorFromMinecraftPalette(mapPalette, (byte) highIndex);
    final int expectedHighColor = colors[highIndex];
    assertEquals(expectedHighColor, highColor, "the byte of index 200 is negative in Java and still names index 200");

    final int reservedColor = DitherUtils.getColorFromMinecraftPalette(mapPalette, (byte) 0);
    assertEquals(0, reservedColor, "a reserved transparent index has no color");
  }

  @Test
  void shouldMapFullyTransparentPixelsToIndexZero() {
    final byte transparent = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, TRANSPARENT_WHITE);
    final byte opaque = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, OPAQUE_WHITE);
    final byte translucent = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, 0x01FFFFFF);
    assertEquals(0, transparent);
    assertEquals(1, opaque);
    assertEquals(1, translucent);
  }

  @Test
  void shouldKeepTheTransparentIndexOfTheMapPaletteForTransparentPixelsOnly() {
    final DitherPalette mapPalette = DitherPalette.DEFAULT_MAP_PALETTE;
    final byte transparent = DitherUtils.getBestColorIncludingTransparent(mapPalette, TRANSPARENT_WHITE);
    final byte opaque = DitherUtils.getBestColorIncludingTransparent(mapPalette, OPAQUE_WHITE);
    final int opaqueIndex = opaque & 0xFF;
    final int reserved = mapPalette.getReservedIndices();
    assertEquals(0, transparent, "index 0 of the map palette is transparent");

    final boolean opaqueIndexReserved = opaqueIndex >= reserved;
    assertTrue(opaqueIndexReserved, "opaque pixels never get a transparent index, got %s".formatted(opaqueIndex));
  }

  @Test
  void shouldReadEveryChannelFromItsOwnPlaceInThePackedColor() {
    final byte red = DitherUtils.getBestColorIncludingTransparent(BLACK_RED_GREEN, 0xFFFF0000);
    final byte green = DitherUtils.getBestColorIncludingTransparent(BLACK_RED_GREEN, 0xFF00FF00);
    final int[] pixels = { 0xFFFF0000, 0xFF00FF00 };
    final byte[] simplified = DitherUtils.simplify(BLACK_RED_GREEN, pixels);
    final byte[] expected = new byte[] { 1, 2 };
    assertEquals(1, red, "a red pixel is red, which only holds if the red channel is read from bits 16 to 23");
    assertEquals(2, green, "and a green pixel is green, read from bits 8 to 15");
    assertArrayEquals(expected, simplified, "whole images read their channels from the same places");
  }

  @Test
  void shouldSimplifyWholeImages() {
    final byte[] indices = DitherUtils.simplify(BLACK_WHITE, new int[] { OPAQUE_BLACK, OPAQUE_WHITE, 0xFF101010 });
    final byte[] expected = new byte[] { 0, 1, 0 };
    assertArrayEquals(expected, indices);
    assertThrows(NullPointerException.class, () -> DitherUtils.simplify(null, new int[0]));
    assertThrows(NullPointerException.class, () -> DitherUtils.simplify(BLACK_WHITE, null));
  }

  @Test
  void shouldIgnoreEvenAnAlphaOfZeroWhenSimplifying() {
    final int[] pixels = { TRANSPARENT_WHITE };
    final byte[] indices = DitherUtils.simplify(BLACK_WHITE, pixels);
    final byte[] expected = new byte[] { 1 };
    assertArrayEquals(expected, indices, "unlike getBestColorIncludingTransparent, simplify maps transparent white to white");
  }

  @Test
  void shouldClampToTheChannelRange() {
    final int below = DitherUtils.clamp(-5);
    final int inside = DitherUtils.clamp(128);
    final int above = DitherUtils.clamp(300);
    final int lowest = DitherUtils.clamp(0);
    final int highest = DitherUtils.clamp(CHANNEL_MAXIMUM);
    assertEquals(0, below);
    assertEquals(128, inside);
    assertEquals(CHANNEL_MAXIMUM, above);
    assertEquals(0, lowest);
    assertEquals(CHANNEL_MAXIMUM, highest);
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(DitherUtils.class);
  }
}
