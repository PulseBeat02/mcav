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

/**
 * A compact temporal residual record (mode 17): a control byte holding the class in its low nibble and the motion form
 * in its high nibble, zero to two motion bytes, then a class-specific body. Every record's length follows from its
 * control byte, so no preceding record is parsed to find it.
 *
 * @param kind       the class, 0 to 8
 * @param form       the motion form: 0 inherits the global vector, 1 adds a signed nibble pair, 2 a signed byte pair
 * @param dx         the horizontal motion delta, in half pixels
 * @param dy         the vertical motion delta, in half pixels
 * @param bodyOffset the offset of the first body byte
 * @param length     the whole record's length, control byte included
 */
public record CompactRecord(int kind, int form, int dx, int dy, int bodyOffset, int length) {
  /** Class 0: a signed 8-bit luma offset. */
  public static final int DC_Y = 0;

  /** Class 1: a 2x2 signed luma grid and a chroma pair. */
  public static final int GRID2_YC = 1;

  /** Class 2: a 4x4 signed 4-bit luma grid and a chroma pair. */
  public static final int GRID4_N4_YC = 2;

  /** Class 3: a 4x4 signed 4-bit luma grid, no chroma. */
  public static final int GRID4_N4_Y = 3;

  /** Class 4: a 4x4 signed 8-bit luma grid and a chroma pair. */
  public static final int GRID4_YC = 4;

  /** Class 5: a DC triple and an index into the static 4x4 vector book. */
  public static final int VQ64 = 5;

  /** Class 6: a DC triple and two indexes into the static 4x2 product books. */
  public static final int PQ64 = 6;

  /** Class 7: a prediction gain and a YCoCg bias; the quantizer must be zero. */
  public static final int GAIN_BIAS = 7;

  /** Class 8: luma DC and x/y slopes, and a chroma pair. */
  public static final int LOW2 = 8;

  /** Body bytes of each class, excluding the control and motion bytes. */
  private static final int[] BODY_BYTES = { 1, 6, 10, 8, 18, 4, 5, 4, 5 };

  /** The motion form that inherits the global vector. */
  public static final int FORM_GLOBAL = 0;

  /** The motion form that adds a signed nibble pair to the global vector. */
  public static final int FORM_NIBBLES = 1;

  /** The motion form that adds a signed byte pair to the global vector. */
  public static final int FORM_BYTES = 2;

  private static final int NIBBLE_BITS = 4;

  private static final int NIBBLE_MASK = 15;

  /** A product-book record's two six-bit indexes leave the top nibble of their second byte clear. */
  private static final int PQ_PADDING = 0xF0;

  /**
   * The body length of a class.
   *
   * @param kind the class, 0 to 8
   * @return the body length in bytes
   */
  public static int bodyBytes(final int kind) {
    return BODY_BYTES[kind];
  }

  /**
   * Parses and validates the record at an offset, like {@code compact.parse_record} of the reference.
   *
   * @param data   the frame bytes; the record must end inside them
   * @param offset the offset of the control byte
   * @param q      the leaf's quantizer
   * @return the record
   * @throws Mcv2Exception if the record is truncated, names an unknown class or motion form, gives the gain class a
   *                       quantizer, or has an out-of-range book index or nonzero index padding
   */
  public static CompactRecord parse(final byte[] data, final int offset, final int q) throws Mcv2Exception {
    if (offset < 0 || offset >= data.length || q < 0 || q > Mcv2Format.MAX_QUANTIZER) {
      throw new Mcv2Exception("Truncated compact control");
    }
    final int control = Byte.toUnsignedInt(data[offset]);
    final int kind = control & NIBBLE_MASK;
    final int form = control >> NIBBLE_BITS;
    if (kind >= BODY_BYTES.length || form > FORM_BYTES || (kind == GAIN_BIAS && q != 0)) {
      throw new Mcv2Exception("Invalid compact class or control");
    }
    final int length = 1 + form + BODY_BYTES[kind];
    if (length > data.length - offset) {
      throw new Mcv2Exception("Truncated compact record");
    }
    final int dx = deltaX(data, offset);
    final int dy = deltaY(data, offset);
    final int last = Byte.toUnsignedInt(data[offset + length - 1]);
    if (kind == VQ64 && last >= ResidualBooks.VECTORS) {
      throw new Mcv2Exception("Invalid VQ index");
    }
    if (kind == PQ64 && (last & PQ_PADDING) != 0) {
      throw new Mcv2Exception("Noncanonical PQ padding");
    }
    return new CompactRecord(kind, form, dx, dy, offset + 1 + form, length);
  }

  /**
   * The horizontal motion delta a record adds to the global vector, from its control and motion bytes.
   *
   * @param data   the bytes holding the record, its motion bytes included
   * @param offset the offset of the control byte
   * @return the delta in half pixels
   */
  public static int deltaX(final byte[] data, final int offset) {
    final int form = Byte.toUnsignedInt(data[offset]) >> NIBBLE_BITS;
    if (form == FORM_NIBBLES) {
      return Mcv2Format.signed(data[offset + 1] & NIBBLE_MASK, NIBBLE_BITS);
    }
    return form == FORM_BYTES ? data[offset + 1] : 0;
  }

  /**
   * The vertical motion delta a record adds to the global vector, from its control and motion bytes.
   *
   * @param data   the bytes holding the record, its motion bytes included
   * @param offset the offset of the control byte
   * @return the delta in half pixels
   */
  public static int deltaY(final byte[] data, final int offset) {
    final int form = Byte.toUnsignedInt(data[offset]) >> NIBBLE_BITS;
    if (form == FORM_NIBBLES) {
      return Mcv2Format.signed(Byte.toUnsignedInt(data[offset + 1]) >> NIBBLE_BITS, NIBBLE_BITS);
    }
    return form == FORM_BYTES ? data[offset + 2] : 0;
  }
}
