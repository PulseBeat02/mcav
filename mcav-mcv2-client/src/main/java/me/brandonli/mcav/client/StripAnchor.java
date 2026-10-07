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
package me.brandonli.mcav.client;

/**
 * An anchor map seen this frame: where its screen is, read from the anchor's symbols, and the top left corner of the map
 * as the game drew it, relative to the camera.
 *
 * @param column  the anchor's column in its screen
 * @param row     the anchor's row in its screen
 * @param columns the screen's width in maps
 * @param rows    the screen's height in maps
 * @param facing  the direction the map's right edge points to: east, south, west or north
 * @param stream  the screen's stream id
 * @param cornerX the map's top left corner, x
 * @param cornerY the map's top left corner, y
 * @param cornerZ the map's top left corner, z
 */
record StripAnchor(int column, int row, int columns, int rows, int facing, long stream, float cornerX, float cornerY, float cornerZ) {
  /** The anchor's fields and its stream, one symbol each after the signature. */
  private static final int COLUMN = 8;

  private static final int ROW = 9;

  private static final int COLUMNS = 10;

  private static final int ROWS = 11;

  private static final int FACING = 12;

  private static final int STREAM_LOW = 13;

  private static final int STREAM_HIGH = 14;

  private static final int SYMBOL_BITS = 6;

  /**
   * Reads an anchor.
   *
   * @param colours the colours of a map {@link Mcv2Maps#isAnchor} accepted
   * @param cornerX the map's top left corner relative to the camera, x
   * @param cornerY y
   * @param cornerZ z
   * @return the anchor
   */
  static StripAnchor of(final byte[] colours, final float cornerX, final float cornerY, final float cornerZ) {
    final long stream = Mcv2Maps.symbol(colours, STREAM_LOW) | ((long) Mcv2Maps.symbol(colours, STREAM_HIGH) << SYMBOL_BITS);
    return new StripAnchor(
      Mcv2Maps.symbol(colours, COLUMN),
      Mcv2Maps.symbol(colours, ROW),
      Mcv2Maps.symbol(colours, COLUMNS),
      Mcv2Maps.symbol(colours, ROWS),
      Mcv2Maps.symbol(colours, FACING),
      stream,
      cornerX,
      cornerY,
      cornerZ
    );
  }
}
