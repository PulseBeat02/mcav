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

import com.google.common.base.MoreObjects;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An anchor map seen this frame: where its screen is, read from the anchor's symbols, and the top left corner of the map
 * as the game drew it, relative to the camera.
 */
final class StripAnchor {

  /** The anchor's fields and its stream, one symbol each after the signature. */
  private static final int COLUMN = 8;
  private static final int ROW = 9;
  private static final int COLUMNS = 10;
  private static final int ROWS = 11;
  private static final int FACING = 12;
  private static final int STREAM_LOW = 13;
  private static final int STREAM_HIGH = 14;

  private static final int SYMBOL_BITS = 6;

  private final int column;
  private final int row;
  private final int columns;
  private final int rows;
  private final int facing;
  private final long stream;
  private final float cornerX;
  private final float cornerY;
  private final float cornerZ;

  private StripAnchor(
    final int column,
    final int row,
    final int columns,
    final int rows,
    final int facing,
    final long stream,
    final float cornerX,
    final float cornerY,
    final float cornerZ
  ) {
    this.column = column;
    this.row = row;
    this.columns = columns;
    this.rows = rows;
    this.facing = facing;
    this.stream = stream;
    this.cornerX = cornerX;
    this.cornerY = cornerY;
    this.cornerZ = cornerZ;
  }

  static StripAnchor of(
    final int column,
    final int row,
    final int columns,
    final int rows,
    final int facing,
    final long stream,
    final float cornerX,
    final float cornerY,
    final float cornerZ
  ) {
    return new StripAnchor(column, row, columns, rows, facing, stream, cornerX, cornerY, cornerZ);
  }

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

  int getColumn() {
    return this.column;
  }

  int getRow() {
    return this.row;
  }

  int getColumns() {
    return this.columns;
  }

  int getRows() {
    return this.rows;
  }

  int getFacing() {
    return this.facing;
  }

  long getStream() {
    return this.stream;
  }

  float getCornerX() {
    return this.cornerX;
  }

  float getCornerY() {
    return this.cornerY;
  }

  float getCornerZ() {
    return this.cornerZ;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final StripAnchor anchor)) {
      return false;
    }

    return (
      this.column == anchor.column &&
      this.row == anchor.row &&
      this.columns == anchor.columns &&
      this.rows == anchor.rows &&
      this.facing == anchor.facing &&
      this.stream == anchor.stream &&
      Float.compare(this.cornerX, anchor.cornerX) == 0 &&
      Float.compare(this.cornerY, anchor.cornerY) == 0 &&
      Float.compare(this.cornerZ, anchor.cornerZ) == 0
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.column, this.row, this.columns, this.rows, this.facing, this.stream, this.cornerX, this.cornerY, this.cornerZ);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("column", this.column)
      .add("row", this.row)
      .add("columns", this.columns)
      .add("rows", this.rows)
      .add("facing", this.facing)
      .add("stream", this.stream)
      .add("cornerX", this.cornerX)
      .add("cornerY", this.cornerY)
      .add("cornerZ", this.cornerZ)
      .toString();
  }
}
