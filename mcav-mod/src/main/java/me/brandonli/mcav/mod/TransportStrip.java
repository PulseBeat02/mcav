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

import java.util.List;

/**
 * The transport strip of a frame, built from the frame's MCV2 maps exactly as the pack's text shaders draw it in a
 * client without a shader pack: every page's symbols in its slot, four symbols in the three bytes of a pixel, and per
 * screen a descriptor row that tells the post chain where the screen is. Rows count from the top of the screen, pixels
 * are RGBA bytes.
 */
final class TransportStrip {

  /** The bytes of a pixel. */
  static final int CHANNELS = 4;

  /** The pixels of a descriptor row the post chain reads. */
  static final int DESCRIPTOR_PIXELS = 64;

  static final float POINT_W = 1F;
  static final float DIRECTION_W = 0F;

  private static final int RED_OFFSET = 0;
  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;
  private static final int ALPHA_OFFSET = 3;

  private static final int OPAQUE = 255;

  private static final int BYTE_MASK = 0xFF;

  private static final int SECOND_BYTE_SHIFT = 8;
  private static final int THIRD_BYTE_SHIFT = 16;
  private static final int HIGH_BYTE_SHIFT = 24;

  private static final int LOWEST_BIT_MASK = 1;

  private static final int SYMBOL_BITS = 6;

  private static final int SYMBOLS_PER_PIXEL = 4;

  /** A page's stream id and page number in its header: bits 64 to 95 and 128 to 143 of its symbol stream. */
  private static final int STREAM_BIT = 64;

  private static final int NUMBER_BIT = 128;

  private static final int HALF_WORD = 16;

  /** The descriptor: "MCV", a version byte, then 28 floats, each in two pixels: three low bytes, then the high one. */
  private static final int SIGNATURE_RED = 'M';
  private static final int SIGNATURE_GREEN = 'C';
  private static final int SIGNATURE_BLUE = 'V';

  private static final int VERSION = 0xA1;

  private static final int FIRST_FLOAT_PIXEL = 2;

  private static final int PIXELS_PER_FLOAT = 2;

  private static final int FLOATS = 28;

  private static final int ORIGIN = 0;
  private static final int RIGHT = 4;
  private static final int DOWN = 8;
  private static final int PROJECTION = 12;

  private static final int W_COMPONENT = 3;

  private static final int MATRIX = 16;

  private static final int ANCHOR_INDEX_STRIDE = 64;

  private static final int AXES = 3;

  private static final int SECOND_COLUMN = 4;
  private static final int THIRD_COLUMN = 8;
  private static final int FOURTH_COLUMN = 12;

  private static final int EAST = 0;
  private static final int SOUTH = 1;
  private static final int WEST = 2;
  private static final int NORTH = 3;

  private TransportStrip() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Builds the strip.
   *
   * @param layout       where the pack keeps its screens' pages and anchors
   * @param width        the screen's width in pixels
   * @param pages        the colours of every page map of this frame
   * @param anchors      the anchor maps of this frame; the first of each screen describes it
   * @param viewRotation the camera's rotation, a column-major 4x4 matrix
   * @param projection   the projection, a column-major 4x4 matrix
   * @return {@link PackLayout#stripRows} rows of {@code width} RGBA pixels, the top row first
   */
  static byte[] build(
    final PackLayout layout,
    final int width,
    final List<byte[]> pages,
    final List<StripAnchor> anchors,
    final float[] viewRotation,
    final float[] projection
  ) {
    final byte[] strip = new byte[layout.stripRows(width) * width * CHANNELS];
    for (final byte[] page : pages) {
      writePage(strip, layout, width, page);
    }
    final boolean[] described = new boolean[layout.screens()];
    for (final StripAnchor anchor : anchors) {
      final int screen = layout.screenOf(anchor.stream());
      if (screen != PackLayout.NO_SCREEN && !described[screen]) {
        described[screen] = true;
        writeDescriptor(strip, (layout.totalSlots() * PackLayout.rowsPerPage(width) + screen) * width, anchor, viewRotation, projection);
      }
    }
    return strip;
  }

  /** Bits of a page's symbol stream, least significant first; a colour outside the alphabet reads as 0, as in the shader. */
  static int bits(final byte[] colours, final int bit, final int count) {
    int value = 0;
    for (int index = 0; index < count; index++) {
      final int at = bit + index;
      final int symbol = symbolOrZero(colours, at / SYMBOL_BITS);
      value |= ((symbol >> (at % SYMBOL_BITS)) & LOWEST_BIT_MASK) << index;
    }
    return value;
  }

  // a colour outside the alphabet reads as 0, as in the shader
  private static int symbolOrZero(final byte[] colours, final int index) {
    final int symbol = Mcv2Maps.symbol(colours, index);
    return Math.max(symbol, 0);
  }

  private static void writePage(final byte[] strip, final PackLayout layout, final int width, final byte[] page) {
    final long stream = bits(page, STREAM_BIT, HALF_WORD) | ((long) bits(page, STREAM_BIT + HALF_WORD, HALF_WORD) << HALF_WORD);
    final int screen = layout.screenOf(stream);
    final int number = bits(page, NUMBER_BIT, HALF_WORD);
    if (screen == PackLayout.NO_SCREEN || number >= layout.slots(screen)) {
      return;
    }

    final int first = (layout.firstSlot(screen) + number) * PackLayout.rowsPerPage(width) * width;
    for (int pixel = 0; pixel < PackLayout.PAGE_PIXELS; pixel++) {
      int word = 0;
      for (int index = 0; index < SYMBOLS_PER_PIXEL; index++) {
        word |= symbolOrZero(page, pixel * SYMBOLS_PER_PIXEL + index) << (SYMBOL_BITS * index);
      }
      put(strip, first + pixel, word & BYTE_MASK, (word >> SECOND_BYTE_SHIFT) & BYTE_MASK, word >> THIRD_BYTE_SHIFT);
    }
  }

  private static void writeDescriptor(
    final byte[] strip,
    final int start,
    final StripAnchor anchor,
    final float[] viewRotation,
    final float[] projection
  ) {
    final float[] floats = descriptorFloats(anchor, viewRotation, projection);
    writeSignature(strip, start);
    writeFloats(strip, start, floats);
  }

  private static float[] descriptorFloats(final StripAnchor anchor, final float[] viewRotation, final float[] projection) {
    final float[] corner = transform(viewRotation, anchor.cornerX(), anchor.cornerY(), anchor.cornerZ(), POINT_W);
    final float[] right = transform(viewRotation, rightX(anchor.facing()), 0, rightZ(anchor.facing()), DIRECTION_W);
    final float[] down = transform(viewRotation, 0, -1, 0, DIRECTION_W);
    final float[] floats = new float[FLOATS];
    for (int axis = 0; axis < AXES; axis++) {
      floats[ORIGIN + axis] = corner[axis] - anchor.column() * right[axis] - anchor.row() * down[axis];
      floats[RIGHT + axis] = right[axis];
      floats[DOWN + axis] = down[axis];
    }
    floats[ORIGIN + W_COMPONENT] = anchor.columns();
    floats[RIGHT + W_COMPONENT] = anchor.rows();
    floats[DOWN + W_COMPONENT] = anchor.column() * ANCHOR_INDEX_STRIDE + anchor.row();
    System.arraycopy(projection, 0, floats, PROJECTION, MATRIX);
    return floats;
  }

  private static void writeSignature(final byte[] strip, final int start) {
    put(strip, start, SIGNATURE_RED, SIGNATURE_GREEN, SIGNATURE_BLUE);
    put(strip, start + 1, VERSION, 0, 0);
  }

  private static void writeFloats(final byte[] strip, final int start, final float[] floats) {
    for (int pixel = FIRST_FLOAT_PIXEL; pixel < DESCRIPTOR_PIXELS; pixel++) {
      writeFloatPixel(strip, start, pixel, floats);
    }
  }

  private static void writeFloatPixel(final byte[] strip, final int start, final int pixel, final float[] floats) {
    final int index = (pixel - FIRST_FLOAT_PIXEL) / PIXELS_PER_FLOAT;
    if (index >= FLOATS) {
      put(strip, start + pixel, 0, 0, 0);
      return;
    }

    final int bits = Float.floatToRawIntBits(floats[index]);
    if (pixel % PIXELS_PER_FLOAT == 0) {
      put(strip, start + pixel, bits & BYTE_MASK, (bits >> SECOND_BYTE_SHIFT) & BYTE_MASK, (bits >> THIRD_BYTE_SHIFT) & BYTE_MASK);
      return;
    }

    put(strip, start + pixel, bits >>> HIGH_BYTE_SHIFT, 0, 0);
  }

  /** The world direction one block along a map's right edge, by facing: east, south, west, north. */
  private static float rightX(final int facing) {
    return switch (facing) {
      case EAST -> 1;
      case WEST -> -1;
      default -> 0;
    };
  }

  private static float rightZ(final int facing) {
    return switch (facing) {
      case SOUTH -> 1;
      case NORTH -> -1;
      default -> 0;
    };
  }

  /** A column-major 4x4 matrix times a vector, the first three components. */
  static float[] transform(final float[] matrix, final float vectorX, final float vectorY, final float vectorZ, final float vectorW) {
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

  private static void put(final byte[] strip, final int pixel, final int red, final int green, final int blue) {
    final int at = pixel * CHANNELS;
    strip[at + RED_OFFSET] = (byte) red;
    strip[at + GREEN_OFFSET] = (byte) green;
    strip[at + BLUE_OFFSET] = (byte) blue;
    strip[at + ALPHA_OFFSET] = (byte) OPAQUE;
  }
}
