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

import java.util.Arrays;

// MCV2 maps as the server draws them
final class MapFixtures {

  // "MCP1", version 1 and six bits, in six-bit symbols
  static final int[] PAGE_PREFIX = { 13, 13, 4, 20, 49, 4, 32 };

  static final int[] ANCHOR_SIGNATURE = { 21, 3, 58, 44, 9, 37, 60, 17 };

  private static final int SYMBOL_BITS = 6;
  private static final int SYMBOL_MASK = 63;

  private static final int FIRST_SYMBOL_COLOR = 4;

  private static final int STREAM_BIT = 64;
  private static final int STREAM_BIT_COUNT = 32;

  private static final int NUMBER_BIT = 128;
  private static final int NUMBER_BIT_COUNT = 16;

  private static final int ANCHOR_SYMBOLS = 16;

  private static final int FIRST_FIELD = 8;

  private static final int CHECKSUM = 15;

  private MapFixtures() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  // the symbols, and symbol 0 after them
  static byte[] colors(final int[] symbols) {
    final byte[] colors = new byte[Mcv2Maps.COLORS];
    Arrays.fill(colors, (byte) FIRST_SYMBOL_COLOR);
    for (int index = 0; index < symbols.length; index++) {
      colors[index] = (byte) (symbols[index] + FIRST_SYMBOL_COLOR);
    }
    return colors;
  }

  // the prefix, the stream id, the number, and filler
  static int[] pageSymbols(final long stream, final int number) {
    final int[] symbols = new int[Mcv2Maps.COLORS];
    for (int index = 0; index < symbols.length; index++) {
      symbols[index] = (index * 7 + 3) & SYMBOL_MASK;
    }
    System.arraycopy(PAGE_PREFIX, 0, symbols, 0, PAGE_PREFIX.length);
    writeBits(symbols, STREAM_BIT, STREAM_BIT_COUNT, stream);
    writeBits(symbols, NUMBER_BIT, NUMBER_BIT_COUNT, number);
    return symbols;
  }

  // the signature, the fields, the low and high six bits of the stream, the checksum
  static int[] anchorSymbols(final int column, final int row, final int columns, final int rows, final int facing, final int stream) {
    final int[] fields = { column, row, columns, rows, facing, stream & SYMBOL_MASK, (stream >> SYMBOL_BITS) & SYMBOL_MASK };
    final int[] symbols = Arrays.copyOf(ANCHOR_SIGNATURE, ANCHOR_SYMBOLS);
    int sum = 0;
    for (int index = 0; index < fields.length; index++) {
      symbols[FIRST_FIELD + index] = fields[index];
      sum += fields[index];
    }
    symbols[CHECKSUM] = sum & SYMBOL_MASK;
    return symbols;
  }

  // least significant bit first, six bits a symbol
  static void writeBits(final int[] symbols, final int bit, final int count, final long value) {
    for (int index = 0; index < count; index++) {
      final int at = bit + index;
      final int shift = at % SYMBOL_BITS;
      final int set = (int) ((value >> index) & 1);
      symbols[at / SYMBOL_BITS] = (symbols[at / SYMBOL_BITS] & ~(1 << shift)) | (set << shift);
    }
  }

  static byte[] withFlippedBits(final byte[] colors, final byte[] masks) {
    final byte[] flipped = colors.clone();
    for (int index = 0; index < masks.length; index++) {
      flipped[index] ^= masks[index];
    }

    return flipped;
  }
}
