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

// tells MCV2's maps apart by their first symbols, as the pack's text shader does; symbol s is map colour s + 4
final class Mcv2Maps {

  static final int COLOURS = 128 * 128;

  static final int NOT_A_SYMBOL = -1;

  private static final int FIRST_SYMBOL_COLOUR = 4;

  private static final int SYMBOLS = 64;

  // "MCP1", version 1 and six bits, in six-bit symbols
  private static final int[] PAGE_PREFIX = { 13, 13, 4, 20, 49, 4, 32 };

  private static final int[] ANCHOR_SIGNATURE = { 21, 3, 58, 44, 9, 37, 60, 17 };

  // the symbols an anchor's checksum adds up
  private static final int FIRST_FIELD = 8;
  private static final int CHECKSUM = 15;

  private static final int FACING = 12;

  private static final int LAST_FACING = 3;

  private Mcv2Maps() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static int symbol(final byte[] colours, final int index) {
    final int symbol = Byte.toUnsignedInt(colours[index]) - FIRST_SYMBOL_COLOUR;
    return symbol >= 0 && symbol < SYMBOLS ? symbol : NOT_A_SYMBOL;
  }

  static boolean isPage(final byte[] colours) {
    return colours.length == COLOURS && starts(colours, PAGE_PREFIX);
  }

  static boolean isAnchor(final byte[] colours) {
    if (colours.length != COLOURS || !starts(colours, ANCHOR_SIGNATURE)) {
      return false;
    }

    int sum = 0;
    for (int index = FIRST_FIELD; index < CHECKSUM; index++) {
      final int field = symbol(colours, index);
      if (field == NOT_A_SYMBOL) {
        return false;
      }
      sum += field;
    }
    return (sum & (SYMBOLS - 1)) == symbol(colours, CHECKSUM) && symbol(colours, FACING) <= LAST_FACING;
  }

  private static boolean starts(final byte[] colours, final int[] prefix) {
    for (int index = 0; index < prefix.length; index++) {
      if (symbol(colours, index) != prefix[index]) {
        return false;
      }
    }
    return true;
  }
}
