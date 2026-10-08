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

/**
 * Tells MCV2's maps apart as the pack's text shader does, by the first symbols of their colours: symbol {@code s} is map
 * colour {@code s + 4}. A transport page starts with the six-bit form of its header's magic, version and symbol width;
 * an anchor, which marks where a screen's maps hang, with a signature, then its fields and their checksum.
 */
final class Mcv2Maps {

  /** The colours of a map: 128 by 128. */
  static final int COLOURS = 128 * 128;

  /** What a colour outside the alphabet reads as. */
  static final int NOT_A_SYMBOL = -1;

  private static final int FIRST_SYMBOL_COLOUR = 4;

  private static final int SYMBOLS = 64;

  /** "MCP1", version 1 and six bits, in six-bit symbols. */
  private static final int[] PAGE_PREFIX = { 13, 13, 4, 20, 49, 4, 32 };

  private static final int[] ANCHOR_SIGNATURE = { 21, 3, 58, 44, 9, 37, 60, 17 };

  /** The first and last symbol an anchor's checksum adds up. */
  private static final int FIRST_FIELD = 8;

  private static final int CHECKSUM = 15;

  private static final int FACING = 12;

  private static final int LAST_FACING = 3;

  private Mcv2Maps() {}

  /**
   * Reads a symbol.
   *
   * @param colours a map's colours
   * @param index   the symbol's index, row by row
   * @return the symbol, or {@link #NOT_A_SYMBOL} for a colour outside the alphabet
   */
  static int symbol(final byte[] colours, final int index) {
    final int symbol = (colours[index] & 0xFF) - FIRST_SYMBOL_COLOUR;
    return symbol >= 0 && symbol < SYMBOLS ? symbol : NOT_A_SYMBOL;
  }

  /** Whether a map is a transport page. */
  static boolean isPage(final byte[] colours) {
    return colours.length == COLOURS && starts(colours, PAGE_PREFIX);
  }

  /** Whether a map is an anchor whose fields are whole: its checksum holds and its facing is one of the four. */
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
