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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;

/**
 * The six-bit alphabet that carries transport symbols as Minecraft map colours: symbol {@code s} is sent as map colour
 * byte {@code s + 4}, the packed colour ids 4 to 67, which are the four brightnesses of the base colours 1 to 16. Their
 * 64 RGB values are distinct on the supported Minecraft 26.3 palette, so the resource pack's
 * shader recovers every symbol exactly from the texel the client uploads.
 */
public final class MapAlphabet {

  /** The useful bits per symbol this alphabet carries. */
  public static final int SYMBOL_BITS = 6;

  /** The map colour byte of symbol 0. */
  public static final int FIRST_COLOR = 4;

  /** The number of symbols. */
  public static final int SIZE = 1 << SYMBOL_BITS;

  private MapAlphabet() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Converts symbols into map colour bytes, padding with symbol 0 up to a whole number of 128-colour rows.
   *
   * @param symbols the symbols, at most 16,384
   * @return a new map-color array, a multiple of 128 bytes; empty input produces an empty array
   * @throws IllegalArgumentException if there are more than 16,384 symbols or an unsigned symbol exceeds 63
   * @throws NullPointerException if {@code symbols} is null
   */
  public static byte[] toMapColors(final byte[] symbols) {
    Preconditions.checkNotNull(symbols, "Symbols must not be null");
    Preconditions.checkArgument(symbols.length <= TransportPages.PAGE_SYMBOLS, "A map holds at most 16384 symbols");
    final byte[] colors = new byte[Math.ceilDiv(symbols.length, TransportPages.MAP_SIDE) * TransportPages.MAP_SIDE];
    for (int index = 0; index < colors.length; index++) {
      final int symbol = index < symbols.length ? Byte.toUnsignedInt(symbols[index]) : 0;
      Preconditions.checkArgument(symbol < SIZE, "Symbol %s is outside the six-bit alphabet", symbol);
      colors[index] = (byte) (symbol + FIRST_COLOR);
    }
    return colors;
  }

  /**
   * Converts map colour bytes back into symbols.
   *
   * @param colors the map colours
   * @return a new symbol array of the same length; any row padding remains present
   * @throws Mcv2Exception if a colour is outside the alphabet
   * @throws NullPointerException if {@code colors} is null
   */
  public static byte[] fromMapColors(final byte[] colors) throws Mcv2Exception {
    Preconditions.checkNotNull(colors, "Colors must not be null");
    final byte[] symbols = new byte[colors.length];
    for (int index = 0; index < colors.length; index++) {
      final int symbol = Byte.toUnsignedInt(colors[index]) - FIRST_COLOR;
      if (symbol < 0 || symbol >= SIZE) {
        throw new Mcv2Exception("Map colour outside the alphabet");
      }
      symbols[index] = (byte) symbol;
    }
    return symbols;
  }
}
