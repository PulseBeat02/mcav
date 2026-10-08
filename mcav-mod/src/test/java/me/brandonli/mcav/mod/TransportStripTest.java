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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TransportStripTest {

  private static final int WIDTH = 854;

  /** Five rows a page at 854 pixels: screen 0 owns slots 0 to 2, screen 1 slots 3 and 4, then a descriptor row each. */
  private static final PackLayout LAYOUT = PackLayout.parse(PackLayoutTest.config("7u, 9u", "3, 2", "0, 3", 2)).orElseThrow();

  private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

  /** A quarter turn about the y axis, column by column: x becomes -z and z becomes x. */
  private static final float[] QUARTER_TURN = { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1 };

  /** Floats whose every byte counts: no trailing zeros in their mantissas. */
  private static final float[] PROJECTION = {
    0.1F,
    1.1F,
    2.3F,
    3.7F,
    4.9F,
    5.3F,
    6.7F,
    7.1F,
    8.9F,
    9.3F,
    10.7F,
    11.1F,
    12.9F,
    13.3F,
    14.7F,
    -15.1F,
  };

  private static final int DESCRIPTORS = 25 * WIDTH;

  @Test
  void aPageFillsItsSlotWithFourSymbolsAPixel() {
    final int[] symbols = MapFixtures.pageSymbols(9, 1);
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(MapFixtures.colours(symbols)), List.of(), IDENTITY, PROJECTION);
    final byte[] expected = new byte[27 * WIDTH * TransportStrip.CHANNELS];
    final int first = 4 * 5 * WIDTH;
    for (int pixel = 0; pixel < PackLayout.PAGE_PIXELS; pixel++) {
      final int word = symbols[4 * pixel] | (symbols[4 * pixel + 1] << 6) | (symbols[4 * pixel + 2] << 12) | (symbols[4 * pixel + 3] << 18);
      put(expected, first + pixel, word & 0xFF, (word >> 8) & 0xFF, word >> 16);
    }
    assertArrayEquals(expected, strip);
  }

  @Test
  void aPageFindsItsScreenByAllThirtyTwoBitsOfItsStream() {
    final PackLayout layout = PackLayout.parse(PackLayoutTest.config("4294901767u", "1", "0", 1)).orElseThrow();
    final int[] symbols = MapFixtures.pageSymbols(0xFFFF_0007L, 0);
    final byte[] strip = TransportStrip.build(layout, WIDTH, List.of(MapFixtures.colours(symbols)), List.of(), IDENTITY, PROJECTION);
    final int word = symbols[0] | (symbols[1] << 6) | (symbols[2] << 12) | (symbols[3] << 18);
    assertPixel(strip, 0, word & 0xFF, (word >> 8) & 0xFF, word >> 16, 255);
  }

  @Test
  void aColourOutsideTheAlphabetReadsAsSymbolZero() {
    final int[] symbols = MapFixtures.pageSymbols(7, 0);
    final byte[] colours = MapFixtures.colours(symbols);
    colours[41] = 3;
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(colours), List.of(), IDENTITY, PROJECTION);
    final int word = symbols[40] | (symbols[42] << 12) | (symbols[43] << 18);
    assertEquals(word & 0xFF, strip[40] & 0xFF);
    assertEquals((word >> 8) & 0xFF, strip[41] & 0xFF);
    assertEquals(word >> 16, strip[42] & 0xFF);
    final byte[] bits = MapFixtures.colours(new int[] { 63, 63 });
    bits[0] = 3;
    assertEquals(0b111111_000000, TransportStrip.bits(bits, 0, 12));
  }

  @Test
  void readsTheStreamAndNumberOfAPage() {
    final byte[] page = MapFixtures.colours(MapFixtures.pageSymbols(0xCAFE_BABEL, 0x1234));
    assertEquals(0xBABE, TransportStrip.bits(page, 64, 16));
    assertEquals(0xCAFE, TransportStrip.bits(page, 80, 16));
    assertEquals(0x1234, TransportStrip.bits(page, 128, 16));
  }

  @Test
  void aPageOfAnotherStreamOrBeyondItsScreensSlotsIsLeftOut() {
    final byte[] empty = new byte[27 * WIDTH * TransportStrip.CHANNELS];
    for (final int[] symbols : List.of(MapFixtures.pageSymbols(8, 0), MapFixtures.pageSymbols(9, 2), MapFixtures.pageSymbols(7, 3))) {
      assertArrayEquals(empty, TransportStrip.build(LAYOUT, WIDTH, List.of(MapFixtures.colours(symbols)), List.of(), IDENTITY, PROJECTION));
    }
  }

  @Test
  void theDescriptorRowTellsTheChainWhereTheScreenIsByFacing() {
    final float[][] rights = { { 1, 0, 0 }, { 0, 0, 1 }, { -1, 0, 0 }, { 0, 0, -1 } };
    for (final float[] rotation : List.of(IDENTITY, QUARTER_TURN)) {
      for (int facing = 0; facing < 4; facing++) {
        final StripAnchor anchor = new StripAnchor(1, 2, 4, 3, facing, 7, 10.1F, 64.3F, -3.7F);
        final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(), List.of(anchor), rotation, PROJECTION);
        final float[] corner = times(rotation, 10.1F, 64.3F, -3.7F, 1);
        final float[] right = times(rotation, rights[facing][0], rights[facing][1], rights[facing][2], 0);
        final float[] down = times(rotation, 0, -1, 0, 0);
        final float[] expected = new float[28];
        for (int axis = 0; axis < 3; axis++) {
          expected[axis] = corner[axis] - right[axis] - 2 * down[axis];
          expected[4 + axis] = right[axis];
          expected[8 + axis] = down[axis];
        }
        expected[3] = 4;
        expected[7] = 3;
        expected[11] = 64 + 2;
        System.arraycopy(PROJECTION, 0, expected, 12, 16);
        assertArrayEquals(expected, descriptor(strip, DESCRIPTORS), "facing " + facing);
        assertPixel(strip, DESCRIPTORS, 0x4D, 0x43, 0x56, 255);
        assertPixel(strip, DESCRIPTORS + 1, 0xA1, 0, 0, 255);
        for (int pixel = 58; pixel < TransportStrip.DESCRIPTOR_PIXELS; pixel++) {
          assertPixel(strip, DESCRIPTORS + pixel, 0, 0, 0, 255);
        }
        assertPixel(strip, DESCRIPTORS + TransportStrip.DESCRIPTOR_PIXELS, 0, 0, 0, 0);
        final byte[] rest = Arrays.copyOfRange(strip, (DESCRIPTORS + WIDTH) * 4, strip.length);
        assertArrayEquals(new byte[rest.length], rest, "screen 1 has no anchor");
        assertArrayEquals(new byte[DESCRIPTORS * 4], Arrays.copyOf(strip, DESCRIPTORS * 4), "no page");
      }
    }
  }

  @Test
  void theFirstAnchorOfAScreenDescribesItAndAnchorsOfOtherStreamsAreLeftOut() {
    final StripAnchor first = new StripAnchor(0, 0, 2, 2, 0, 9, 1, 2, 3);
    final StripAnchor second = new StripAnchor(1, 1, 2, 2, 0, 9, 7, 8, 9);
    final StripAnchor other = new StripAnchor(0, 0, 2, 2, 0, 8, 4, 5, 6);
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(), List.of(other, first, second), IDENTITY, PROJECTION);
    final float[] floats = descriptor(strip, DESCRIPTORS + WIDTH);
    assertArrayEquals(new float[] { 1, 2, 3 }, Arrays.copyOf(floats, 3));
    assertArrayEquals(new byte[WIDTH * 4], Arrays.copyOfRange(strip, DESCRIPTORS * 4, (DESCRIPTORS + WIDTH) * 4), "screen 0 has none");
  }

  @Test
  void aMatrixTimesAVectorIsItsColumnsWeighted() {
    final float[] matrix = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
    assertArrayEquals(new float[] { 1 + 10 + 27 + 52, 2 + 12 + 30 + 56, 3 + 14 + 33 + 60 }, TransportStrip.transform(matrix, 1, 2, 3, 4));
  }

  private static float[] times(final float[] matrix, final float vectorX, final float vectorY, final float vectorZ, final float vectorW) {
    final float[] out = new float[3];
    for (int row = 0; row < 3; row++) {
      out[row] = matrix[row] * vectorX + matrix[4 + row] * vectorY + matrix[8 + row] * vectorZ + matrix[12 + row] * vectorW;
    }
    return out;
  }

  /** The 28 floats of a descriptor row: each in two pixels, the three low bytes in the first, the high byte next. */
  private static float[] descriptor(final byte[] strip, final int start) {
    final float[] floats = new float[28];
    for (int index = 0; index < floats.length; index++) {
      final int low = (start + 2 + 2 * index) * 4;
      final int high = low + 4;
      final int bits =
        (strip[low] & 0xFF) | ((strip[low + 1] & 0xFF) << 8) | ((strip[low + 2] & 0xFF) << 16) | ((strip[high] & 0xFF) << 24);
      assertEquals(0, strip[high + 1] | strip[high + 2], "the high pixel holds one byte");
      assertEquals(255, strip[low + 3] & 0xFF);
      assertEquals(255, strip[high + 3] & 0xFF);
      floats[index] = Float.intBitsToFloat(bits);
    }
    return floats;
  }

  private static void assertPixel(final byte[] strip, final int pixel, final int red, final int green, final int blue, final int alpha) {
    assertArrayEquals(
      new byte[] { (byte) red, (byte) green, (byte) blue, (byte) alpha },
      Arrays.copyOfRange(strip, pixel * 4, pixel * 4 + 4)
    );
  }

  private static void put(final byte[] strip, final int pixel, final int red, final int green, final int blue) {
    strip[pixel * 4] = (byte) red;
    strip[pixel * 4 + 1] = (byte) green;
    strip[pixel * 4 + 2] = (byte) blue;
    strip[pixel * 4 + 3] = (byte) 255;
  }
}
