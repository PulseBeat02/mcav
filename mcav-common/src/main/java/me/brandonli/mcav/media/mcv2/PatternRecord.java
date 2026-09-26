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
package me.brandonli.mcav.media.mcv2;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A pattern palette record (mode 18) resolved against its frame's tables: two RGB endpoints, an orientation, and one
 * selector bit per column (orientation 0) or per row (orientation 1), repeated along the other axis.
 *
 * <p>The stored record is the endpoints (six bytes, or a one-byte index into the frame's endpoint table), then the
 * orientation byte and {@code size / 8} axis bytes (or a one-byte index into the frame's selector table for this size).
 *
 */
public final class PatternRecord {

  /** Two RGB endpoints. */
  private static final int ENDPOINT_BYTES = Mcv2Format.PALETTE_COLORS * Mcv2Format.CHANNELS;

  private final int color0;

  private final int color1;

  private final int orientation;

  private final byte[] axis;

  private PatternRecord(final int color0, final int color1, final int orientation, final byte[] axis) {
    this.color0 = color0;
    this.color1 = color1;
    this.orientation = orientation;
    this.axis = axis;
  }

  /**
   * Resolves and validates a pattern record, like {@code pattern.expand_record} of the reference.
   *
   * @param data      the frame bytes
   * @param offset    the record offset
   * @param size      the leaf size, 8, 16 or 32
   * @param endpoints the frame's endpoint table as six-byte RGB pairs, or null when the record stores its endpoints
   * @param selectors the frame's selector table for this size, or null when the record stores its selector word
   * @return the resolved record
   * @throws Mcv2Exception if the record is truncated, an index is outside its table, or the orientation is not 0 or 1
   */
  public static PatternRecord expand(
    final byte[] data,
    final int offset,
    final int size,
    final byte@Nullable[] endpoints,
    final byte@Nullable[] selectors
  ) throws Mcv2Exception {
    final int length = Mcv2Format.patternSize(size, endpoints != null, selectors != null);
    final int head = endpoints != null ? 1 : ENDPOINT_BYTES;
    if (offset < 0 || length > data.length - offset) {
      throw new Mcv2Exception("Invalid palette pattern record");
    }
    final byte[] colors;
    int colorsAt;
    if (endpoints == null) {
      colors = data;
      colorsAt = offset;
    } else {
      final int which = Byte.toUnsignedInt(data[offset]);
      if ((which + 1) * ENDPOINT_BYTES > endpoints.length) {
        throw new Mcv2Exception("Endpoint index outside the table");
      }
      colors = endpoints;
      colorsAt = which * ENDPOINT_BYTES;
    }
    final int entry = 1 + size / Byte.SIZE;
    final byte[] word;
    int wordAt;
    if (selectors == null) {
      word = data;
      wordAt = offset + head;
    } else {
      final int which = Byte.toUnsignedInt(data[offset + head]);
      if ((which + 1) * entry > selectors.length) {
        throw new Mcv2Exception("Selector index outside the table");
      }
      word = selectors;
      wordAt = which * entry;
    }
    final int orientation = Byte.toUnsignedInt(word[wordAt]);
    if (orientation > 1) {
      throw new Mcv2Exception("Invalid palette pattern record");
    }
    final byte[] axis = new byte[size / Byte.SIZE];
    System.arraycopy(word, wordAt + 1, axis, 0, axis.length);
    return new PatternRecord(rgb(colors, colorsAt), rgb(colors, colorsAt + Mcv2Format.CHANNELS), orientation, axis);
  }

  private static int rgb(final byte[] bytes, final int at) {
    return ((bytes[at] & 0xFF) << 16) | ((bytes[at + 1] & 0xFF) << 8) | (bytes[at + 2] & 0xFF);
  }

  /**
   * Gets the first endpoint.
   *
   * @return the colour as {@code 0xRRGGBB}
   */
  public int getColor0() {
    return this.color0;
  }

  /**
   * Gets the second endpoint.
   *
   * @return the colour as {@code 0xRRGGBB}
   */
  public int getColor1() {
    return this.color1;
  }

  /**
   * Gets the orientation.
   *
   * @return 0 when the selector follows x, 1 when it follows y
   */
  public int getOrientation() {
    return this.orientation;
  }

  /**
   * Gets the selector of one position along the pattern's axis.
   *
   * @param index the column (orientation 0) or row (orientation 1) inside the leaf
   * @return 0 or 1
   */
  public int getSelector(final int index) {
    return (this.axis[index / Byte.SIZE] >> (index % Byte.SIZE)) & 1;
  }

  /**
   * The colour of a pixel of the leaf.
   *
   * @param x the column inside the leaf
   * @param y the row inside the leaf
   * @return the colour as {@code 0xRRGGBB}
   */
  public int colorAt(final int x, final int y) {
    final int index = this.orientation == 0 ? x : y;
    return this.getSelector(index) == 0 ? this.color0 : this.color1;
  }
}
