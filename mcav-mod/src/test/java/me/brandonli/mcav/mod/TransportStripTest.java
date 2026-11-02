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

  // five rows a page at 854 pixels: screen 0 owns slots 0 to 2, screen 1 slots 3 and 4, then a descriptor row each
  private static final PackLayout LAYOUT = PackLayout.parse(PackLayoutTest.packConstants("7u, 9u", "3, 2", "0, 3", 2)).orElseThrow();

  private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

  // a quarter turn about the y axis, column by column: x becomes -z and z becomes x
  private static final float[] QUARTER_TURN = { 0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1 };

  // every byte of these floats counts: no trailing zeros in their mantissas
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

  private static final int ROWS = 27;

  private static final int CHANNELS = 4;

  private static final int OPAQUE = 255;

  private static final int BYTE_MASK = 0xFF;

  private static final int SECOND_BYTE_SHIFT = 8;
  private static final int THIRD_BYTE_SHIFT = 16;
  private static final int HIGH_BYTE_SHIFT = 24;

  private static final int SECOND_SYMBOL_SHIFT = 6;
  private static final int THIRD_SYMBOL_SHIFT = 12;
  private static final int FOURTH_SYMBOL_SHIFT = 18;

  private static final int DESCRIPTOR_FLOATS = 28;

  private static final int RIGHT_OFFSET = 4;
  private static final int DOWN_OFFSET = 8;
  private static final int PROJECTION_OFFSET = 12;

  private static final int W_COMPONENT = 3;

  private static final int FLOATS_PER_MATRIX = 16;

  private static final int ANCHOR_INDEX_STRIDE = 64;

  private static final int AXES = 3;

  private static final int SECOND_COLUMN = 4;
  private static final int THIRD_COLUMN = 8;
  private static final int FOURTH_COLUMN = 12;

  @Test
  void shouldFillTheSlotOfAPageWithFourSymbolsAPixel() {
    final int[] symbols = MapFixtures.pageSymbols(9, 1);
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(MapFixtures.colors(symbols)), List.of(), IDENTITY, PROJECTION);
    final byte[] expected = new byte[ROWS * WIDTH * CHANNELS];
    final int first = 4 * 5 * WIDTH;
    for (int pixel = 0; pixel < PackLayout.PAGE_PIXELS; pixel++) {
      final int word =
        symbols[4 * pixel] |
        (symbols[4 * pixel + 1] << SECOND_SYMBOL_SHIFT) |
        (symbols[4 * pixel + 2] << THIRD_SYMBOL_SHIFT) |
        (symbols[4 * pixel + 3] << FOURTH_SYMBOL_SHIFT);
      this.put(expected, first + pixel, word & BYTE_MASK, (word >> SECOND_BYTE_SHIFT) & BYTE_MASK, word >> THIRD_BYTE_SHIFT);
    }
    assertArrayEquals(expected, strip);
  }

  @Test
  void shouldFindTheScreenOfAPageByAllThirtyTwoBitsOfItsStream() {
    final PackLayout layout = PackLayout.parse(PackLayoutTest.packConstants("4294901767u", "1", "0", 1)).orElseThrow();
    final int[] symbols = MapFixtures.pageSymbols(0xFFFF_0007L, 0);
    final byte[] strip = TransportStrip.build(layout, WIDTH, List.of(MapFixtures.colors(symbols)), List.of(), IDENTITY, PROJECTION);
    final int word =
      symbols[0] | (symbols[1] << SECOND_SYMBOL_SHIFT) | (symbols[2] << THIRD_SYMBOL_SHIFT) | (symbols[3] << FOURTH_SYMBOL_SHIFT);
    this.assertPixel(strip, 0, word & BYTE_MASK, (word >> SECOND_BYTE_SHIFT) & BYTE_MASK, word >> THIRD_BYTE_SHIFT, OPAQUE);
  }

  @Test
  void shouldReadAColorOutsideTheAlphabetAsSymbolZero() {
    final int[] symbols = MapFixtures.pageSymbols(7, 0);
    final byte[] colors = MapFixtures.colors(symbols);
    colors[41] = 3;
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(colors), List.of(), IDENTITY, PROJECTION);
    final int word = symbols[40] | (symbols[42] << THIRD_SYMBOL_SHIFT) | (symbols[43] << FOURTH_SYMBOL_SHIFT);
    final int red = strip[40] & BYTE_MASK;
    final int green = strip[41] & BYTE_MASK;
    final int blue = strip[42] & BYTE_MASK;
    final int expectedRed = word & BYTE_MASK;
    final int expectedGreen = (word >> SECOND_BYTE_SHIFT) & BYTE_MASK;
    final int expectedBlue = word >> THIRD_BYTE_SHIFT;
    assertEquals(expectedRed, red);
    assertEquals(expectedGreen, green);
    assertEquals(expectedBlue, blue);
    final byte[] bits = MapFixtures.colors(new int[] { 63, 63 });
    bits[0] = 3;
    final int firstTwelveBits = TransportStrip.readBits(bits, 0, 12);
    assertEquals(0b111111_000000, firstTwelveBits);
  }

  @Test
  void shouldReadTheStreamAndNumberOfAPage() {
    final byte[] page = MapFixtures.colors(MapFixtures.pageSymbols(0xCAFE_BABEL, 0x1234));
    final int lowStreamHalf = TransportStrip.readBits(page, 64, 16);
    final int highStreamHalf = TransportStrip.readBits(page, 80, 16);
    final int pageNumber = TransportStrip.readBits(page, 128, 16);
    assertEquals(0xBABE, lowStreamHalf);
    assertEquals(0xCAFE, highStreamHalf);
    assertEquals(0x1234, pageNumber);
  }

  @Test
  void shouldLeaveOutAPageOfAnotherStreamOrBeyondItsScreensSlots() {
    final byte[] empty = new byte[ROWS * WIDTH * CHANNELS];
    for (final int[] symbols : List.of(MapFixtures.pageSymbols(8, 0), MapFixtures.pageSymbols(9, 2), MapFixtures.pageSymbols(7, 3))) {
      final byte[] built = TransportStrip.build(LAYOUT, WIDTH, List.of(MapFixtures.colors(symbols)), List.of(), IDENTITY, PROJECTION);
      assertArrayEquals(empty, built);
    }
  }

  @Test
  void shouldTellTheChainWhereTheScreenIsByFacingInTheDescriptorRow() {
    final float[][] rights = { { 1, 0, 0 }, { 0, 0, 1 }, { -1, 0, 0 }, { 0, 0, -1 } };
    for (final float[] rotation : List.of(IDENTITY, QUARTER_TURN)) {
      for (int facing = 0; facing < 4; facing++) {
        this.assertDescriptorRow(rotation, facing, rights[facing]);
      }
    }
  }

  @Test
  void shouldDescribeAScreenByItsFirstAnchorAndLeaveOutAnchorsOfOtherStreams() {
    final StripAnchor first = StripAnchor.of(0, 0, 2, 2, 0, 9, 1, 2, 3);
    final StripAnchor second = StripAnchor.of(1, 1, 2, 2, 0, 9, 7, 8, 9);
    final StripAnchor other = StripAnchor.of(0, 0, 2, 2, 0, 8, 4, 5, 6);
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(), List.of(other, first, second), IDENTITY, PROJECTION);
    final float[] floats = this.descriptor(strip, DESCRIPTORS + WIDTH);
    final float[] corner = Arrays.copyOf(floats, AXES);
    final byte[] firstScreenRow = Arrays.copyOfRange(strip, DESCRIPTORS * CHANNELS, (DESCRIPTORS + WIDTH) * CHANNELS);
    final float[] expectedCorner = { 1, 2, 3 };
    final byte[] expectedFirstScreenRow = new byte[WIDTH * CHANNELS];
    assertArrayEquals(expectedCorner, corner);
    assertArrayEquals(expectedFirstScreenRow, firstScreenRow, "screen 0 has none");
  }

  @Test
  void shouldMultiplyAMatrixByAVectorAsItsWeightedColumns() {
    final float[] matrix = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
    final float[] transformed = TransportStrip.transform(matrix, 1, 2, 3, 4);
    final float[] expected = { 1 + 10 + 27 + 52, 2 + 12 + 30 + 56, 3 + 14 + 33 + 60 };
    assertArrayEquals(expected, transformed);
  }

  @Test
  void shouldNotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(TransportStrip.class);
  }

  private void assertDescriptorRow(final float[] rotation, final int facing, final float[] rightDirection) {
    final StripAnchor anchor = StripAnchor.of(1, 2, 4, 3, facing, 7, 10.1F, 64.3F, -3.7F);
    final byte[] strip = TransportStrip.build(LAYOUT, WIDTH, List.of(), List.of(anchor), rotation, PROJECTION);
    final float[] corner = this.times(rotation, 10.1F, 64.3F, -3.7F, 1);
    final float[] right = this.times(rotation, rightDirection[0], rightDirection[1], rightDirection[2], 0);
    final float[] down = this.times(rotation, 0, -1, 0, 0);
    final float[] expected = new float[DESCRIPTOR_FLOATS];
    for (int axis = 0; axis < AXES; axis++) {
      expected[axis] = corner[axis] - right[axis] - 2 * down[axis];
      expected[RIGHT_OFFSET + axis] = right[axis];
      expected[DOWN_OFFSET + axis] = down[axis];
    }
    expected[W_COMPONENT] = 4;
    expected[RIGHT_OFFSET + W_COMPONENT] = 3;
    expected[DOWN_OFFSET + W_COMPONENT] = ANCHOR_INDEX_STRIDE + 2;
    System.arraycopy(PROJECTION, 0, expected, PROJECTION_OFFSET, FLOATS_PER_MATRIX);
    final float[] descriptor = this.descriptor(strip, DESCRIPTORS);
    assertArrayEquals(expected, descriptor, "facing " + facing);
    this.assertPixel(strip, DESCRIPTORS, 0x4D, 0x43, 0x56, OPAQUE);
    this.assertPixel(strip, DESCRIPTORS + 1, 0xA1, 0, 0, OPAQUE);
    for (int pixel = 58; pixel < TransportStrip.DESCRIPTOR_PIXELS; pixel++) {
      this.assertPixel(strip, DESCRIPTORS + pixel, 0, 0, 0, OPAQUE);
    }
    this.assertPixel(strip, DESCRIPTORS + TransportStrip.DESCRIPTOR_PIXELS, 0, 0, 0, 0);
    final byte[] rest = Arrays.copyOfRange(strip, (DESCRIPTORS + WIDTH) * CHANNELS, strip.length);
    final byte[] leadingPixels = Arrays.copyOf(strip, DESCRIPTORS * CHANNELS);
    final byte[] expectedRest = new byte[rest.length];
    final byte[] expectedLeadingPixels = new byte[DESCRIPTORS * CHANNELS];
    assertArrayEquals(expectedRest, rest, "screen 1 has no anchor");
    assertArrayEquals(expectedLeadingPixels, leadingPixels, "no page");
  }

  private float[] times(final float[] matrix, final float vectorX, final float vectorY, final float vectorZ, final float vectorW) {
    final float[] transformed = new float[AXES];
    for (int row = 0; row < AXES; row++) {
      transformed[row] =
        matrix[row] * vectorX +
        matrix[SECOND_COLUMN + row] * vectorY +
        matrix[THIRD_COLUMN + row] * vectorZ +
        matrix[FOURTH_COLUMN + row] * vectorW;
    }

    return transformed;
  }

  // each float in two pixels: the three low bytes in the first, the high byte next
  private float[] descriptor(final byte[] strip, final int start) {
    final float[] floats = new float[DESCRIPTOR_FLOATS];
    for (int index = 0; index < floats.length; index++) {
      final int low = (start + 2 + 2 * index) * CHANNELS;
      final int high = low + CHANNELS;
      final int bits =
        (strip[low] & BYTE_MASK) |
        ((strip[low + 1] & BYTE_MASK) << SECOND_BYTE_SHIFT) |
        ((strip[low + 2] & BYTE_MASK) << THIRD_BYTE_SHIFT) |
        ((strip[high] & BYTE_MASK) << HIGH_BYTE_SHIFT);
      final int highPixelBytes = strip[high + 1] | strip[high + 2];
      final int lowAlpha = strip[low + 3] & BYTE_MASK;
      final int highAlpha = strip[high + 3] & BYTE_MASK;
      assertEquals(0, highPixelBytes, "the high pixel holds one byte");
      assertEquals(OPAQUE, lowAlpha);
      assertEquals(OPAQUE, highAlpha);
      floats[index] = Float.intBitsToFloat(bits);
    }

    return floats;
  }

  private void assertPixel(final byte[] strip, final int pixel, final int red, final int green, final int blue, final int alpha) {
    final byte[] pixelBytes = Arrays.copyOfRange(strip, pixel * CHANNELS, pixel * CHANNELS + CHANNELS);
    final byte[] expected = { (byte) red, (byte) green, (byte) blue, (byte) alpha };
    assertArrayEquals(expected, pixelBytes);
  }

  private void put(final byte[] strip, final int pixel, final int red, final int green, final int blue) {
    strip[pixel * CHANNELS] = (byte) red;
    strip[pixel * CHANNELS + 1] = (byte) green;
    strip[pixel * CHANNELS + 2] = (byte) blue;
    strip[pixel * CHANNELS + 3] = (byte) OPAQUE;
  }
}
