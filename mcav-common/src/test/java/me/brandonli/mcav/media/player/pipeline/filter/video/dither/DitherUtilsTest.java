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

/**
 * Tests {@link DitherUtils}.
 */
final class DitherUtilsTest {

  private static final DitherPalette BLACK_WHITE = DitherPalette.colors(0x000000, 0xFFFFFF);
  private static final DitherPalette BLACK_RED_GREEN = DitherPalette.colors(0x000000, 0xFF0000, 0x00FF00);

  @Test
  void dropsTheLowestBitOfEveryChannelForTheLookup() {
    final int black = DitherUtils.getLookupIndex(0, 0, 0);
    final int white = DitherUtils.getLookupIndex(255, 255, 255);
    final int mixed = DitherUtils.getLookupIndex(2, 4, 6);
    final int roundedDown = DitherUtils.getLookupIndex(3, 5, 7);
    assertEquals(0, black);
    assertEquals((127 << 14) | (127 << 7) | 127, white);
    assertEquals((1 << 14) | (2 << 7) | 3, mixed);
    assertEquals(mixed, roundedDown);
  }

  @Test
  void findsTheClosestPaletteEntry() {
    final byte dark = DitherUtils.getBestColor(BLACK_WHITE, 10, 10, 10);
    final byte bright = DitherUtils.getBestColor(BLACK_WHITE, 250, 250, 250);
    final int darkColor = DitherUtils.getBestFullColor(BLACK_WHITE, 10, 10, 10);
    final int brightColor = DitherUtils.getBestColorNormal(BLACK_WHITE, 250, 250, 250);
    final int indexedColor = DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 1);
    assertEquals(0, dark);
    assertEquals(1, bright);
    assertEquals(0xFF000000, darkColor);
    assertEquals(0xFFFFFFFF, brightColor);
    assertEquals(0xFFFFFFFF, indexedColor);
  }

  @Test
  void rejectsColorComponentsOutsideTheChannelRange() {
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColor(BLACK_WHITE, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColor(BLACK_WHITE, 0, 256, 0));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestFullColor(BLACK_WHITE, 0, 0, 256));
    assertThrows(IllegalArgumentException.class, () -> DitherUtils.getBestColorNormal(BLACK_WHITE, 0, 0, -1));
  }

  @Test
  void rejectsMissingPalettes() {
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestColor(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestFullColor(null, 0, 0, 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getColorFromMinecraftPalette(null, (byte) 0));
    assertThrows(NullPointerException.class, () -> DitherUtils.getBestColorIncludingTransparent(null, 0xFFFFFFFF));
  }

  @Test
  void rejectsPaletteIndicesThePaletteDoesNotHave() {
    final int lastColor = DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 1);
    assertEquals(0xFFFFFFFF, lastColor);
    assertThrows(IndexOutOfBoundsException.class, () -> DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) 2));
    assertThrows(IndexOutOfBoundsException.class, () -> DitherUtils.getColorFromMinecraftPalette(BLACK_WHITE, (byte) -1));
  }

  @Test
  void mapsFullyTransparentPixelsToIndexZero() {
    final byte transparent = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, 0x00FFFFFF);
    final byte opaque = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, 0xFFFFFFFF);
    final byte translucent = DitherUtils.getBestColorIncludingTransparent(BLACK_WHITE, 0x01FFFFFF);
    assertEquals(0, transparent);
    assertEquals(1, opaque);
    assertEquals(1, translucent);
  }

  @Test
  void keepsTheTransparentIndexOfTheMapPaletteForTransparentPixelsOnly() {
    final DitherPalette mapPalette = DitherPalette.DEFAULT_MAP_PALETTE;
    final byte transparent = DitherUtils.getBestColorIncludingTransparent(mapPalette, 0x00FFFFFF);
    final byte opaque = DitherUtils.getBestColorIncludingTransparent(mapPalette, 0xFFFFFFFF);
    final int opaqueIndex = opaque & 0xFF;
    final int reserved = mapPalette.getReservedIndices();
    assertEquals(0, transparent, "index 0 of the map palette is transparent");
    assertTrue(opaqueIndex >= reserved, "opaque pixels never get a transparent index, got " + opaqueIndex);
  }

  @Test
  void readsEveryChannelFromItsOwnPlaceInThePackedColor() {
    final byte red = DitherUtils.getBestColorIncludingTransparent(BLACK_RED_GREEN, 0xFFFF0000);
    final byte green = DitherUtils.getBestColorIncludingTransparent(BLACK_RED_GREEN, 0xFF00FF00);
    final int[] pixels = { 0xFFFF0000, 0xFF00FF00 };
    final byte[] simplified = DitherUtils.simplify(BLACK_RED_GREEN, pixels);
    assertEquals(1, red, "a red pixel is red, which only holds if the red channel is read from bits 16 to 23");
    assertEquals(2, green, "and a green pixel is green, read from bits 8 to 15");
    assertArrayEquals(new byte[] { 1, 2 }, simplified, "whole images read their channels from the same places");
  }

  @Test
  void simplifiesWholeImages() {
    final byte[] indices = DitherUtils.simplify(BLACK_WHITE, new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF101010 });
    assertArrayEquals(new byte[] { 0, 1, 0 }, indices);
    assertThrows(NullPointerException.class, () -> DitherUtils.simplify(null, new int[0]));
    assertThrows(NullPointerException.class, () -> DitherUtils.simplify(BLACK_WHITE, null));
  }

  @Test
  void clampsToTheChannelRange() {
    final int below = DitherUtils.clamp(-5);
    final int inside = DitherUtils.clamp(128);
    final int above = DitherUtils.clamp(300);
    final int lowest = DitherUtils.clamp(0);
    final int highest = DitherUtils.clamp(255);
    assertEquals(0, below);
    assertEquals(128, inside);
    assertEquals(255, above);
    assertEquals(0, lowest);
    assertEquals(255, highest);
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(DitherUtils.class);
  }
}
