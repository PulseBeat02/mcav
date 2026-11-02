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

// built exactly as the pack's text shaders draw it without a shader pack; rows count from the top, pixels are RGBA
final class TransportStrip {

  static final int CHANNELS = 4;

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

  private static final int SYMBOLS_PER_PIXEL = 4;

  // a page header's stream id and page number: bits 64 to 95 and 128 to 143 of its symbol stream
  private static final int STREAM_BIT = 64;
  private static final int NUMBER_BIT = 128;

  private static final int HALF_WORD = 16;

  // the descriptor: "MCV", a version byte, then 28 floats, each in two pixels: three low bytes, then the high one
  private static final int SIGNATURE_RED = 'M';
  private static final int SIGNATURE_GREEN = 'C';
  private static final int SIGNATURE_BLUE = 'V';

  private static final int VERSION = 0xA1;

  private static final int FIRST_FLOAT_PIXEL = 2;

  private static final int PIXELS_PER_FLOAT = 2;

  private static final int DESCRIPTOR_FLOATS = 28;

  private static final int ORIGIN_OFFSET = 0;
  private static final int RIGHT_OFFSET = 4;
  private static final int DOWN_OFFSET = 8;
  private static final int PROJECTION_OFFSET = 12;

  private static final int W_COMPONENT = 3;

  private static final int ANCHOR_INDEX_STRIDE = 64;

  private static final float RIGHT_Y = 0F;

  private static final float DOWN_X = 0F;
  private static final float DOWN_Y = -1F;
  private static final float DOWN_Z = 0F;

  private static final int MATRIX_START = 0;

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

  // the first anchor of each screen describes it; matrices are column-major
  static byte[] build(
    final PackLayout layout,
    final int width,
    final List<byte[]> pages,
    final List<StripAnchor> anchors,
    final float[] viewRotation,
    final float[] projection
  ) {
    final byte[] strip = new byte[layout.getStripRows(width) * width * CHANNELS];
    for (final byte[] page : pages) {
      writePage(strip, layout, width, page);
    }
    final boolean[] described = new boolean[layout.getScreenCount()];
    for (final StripAnchor anchor : anchors) {
      final int screen = layout.findScreen(anchor.getStream());
      if (screen != PackLayout.NO_SCREEN && !described[screen]) {
        described[screen] = true;
        writeDescriptor(
          strip,
          (layout.getTotalSlots() * PackLayout.getRowsPerPage(width) + screen) * width,
          anchor,
          viewRotation,
          projection
        );
      }
    }
    return strip;
  }

  // least significant first
  static int readBits(final byte[] colors, final int bit, final int count) {
    int value = 0;
    for (int index = 0; index < count; index++) {
      final int at = bit + index;
      final int symbol = symbolOrZero(colors, at / Mcv2Maps.SYMBOL_BITS);
      value |= ((symbol >> (at % Mcv2Maps.SYMBOL_BITS)) & LOWEST_BIT_MASK) << index;
    }
    return value;
  }

  // a color outside the alphabet reads as 0, as in the shader
  private static int symbolOrZero(final byte[] colors, final int index) {
    final int symbol = Mcv2Maps.symbol(colors, index);
    return Math.max(symbol, 0);
  }

  private static void writePage(final byte[] strip, final PackLayout layout, final int width, final byte[] page) {
    final long stream = readBits(page, STREAM_BIT, HALF_WORD) | ((long) readBits(page, STREAM_BIT + HALF_WORD, HALF_WORD) << HALF_WORD);
    final int screen = layout.findScreen(stream);
    final int number = readBits(page, NUMBER_BIT, HALF_WORD);
    if (screen == PackLayout.NO_SCREEN || number >= layout.getSlotCount(screen)) {
      return;
    }

    final int firstPixel = (layout.getFirstSlot(screen) + number) * PackLayout.getRowsPerPage(width) * width;
    for (int pixel = 0; pixel < PackLayout.PAGE_PIXELS; pixel++) {
      int word = 0;
      for (int index = 0; index < SYMBOLS_PER_PIXEL; index++) {
        word |= symbolOrZero(page, pixel * SYMBOLS_PER_PIXEL + index) << (Mcv2Maps.SYMBOL_BITS * index);
      }
      writePixel(strip, firstPixel + pixel, word & BYTE_MASK, (word >> SECOND_BYTE_SHIFT) & BYTE_MASK, word >> THIRD_BYTE_SHIFT);
    }
  }

  private static void writeDescriptor(
    final byte[] strip,
    final int start,
    final StripAnchor anchor,
    final float[] viewRotation,
    final float[] projection
  ) {
    final float[] floats = computeDescriptorFloats(anchor, viewRotation, projection);
    writeSignature(strip, start);
    writeFloats(strip, start, floats);
  }

  private static float[] computeDescriptorFloats(final StripAnchor anchor, final float[] viewRotation, final float[] projection) {
    final float[] corner = transform(viewRotation, anchor.getCornerX(), anchor.getCornerY(), anchor.getCornerZ(), POINT_W);
    final float[] right = transform(viewRotation, rightX(anchor.getFacing()), RIGHT_Y, rightZ(anchor.getFacing()), DIRECTION_W);
    final float[] down = transform(viewRotation, DOWN_X, DOWN_Y, DOWN_Z, DIRECTION_W);
    final float[] floats = new float[DESCRIPTOR_FLOATS];
    for (int axis = 0; axis < AXES; axis++) {
      floats[ORIGIN_OFFSET + axis] = corner[axis] - anchor.getColumn() * right[axis] - anchor.getRow() * down[axis];
      floats[RIGHT_OFFSET + axis] = right[axis];
      floats[DOWN_OFFSET + axis] = down[axis];
    }
    floats[ORIGIN_OFFSET + W_COMPONENT] = anchor.getColumns();
    floats[RIGHT_OFFSET + W_COMPONENT] = anchor.getRows();
    floats[DOWN_OFFSET + W_COMPONENT] = anchor.getColumn() * ANCHOR_INDEX_STRIDE + anchor.getRow();
    System.arraycopy(projection, MATRIX_START, floats, PROJECTION_OFFSET, GameMatrices.FLOATS_PER_MATRIX);
    return floats;
  }

  private static void writeSignature(final byte[] strip, final int start) {
    writePixel(strip, start, SIGNATURE_RED, SIGNATURE_GREEN, SIGNATURE_BLUE);
    writePixel(strip, start + 1, VERSION, 0, 0);
  }

  private static void writeFloats(final byte[] strip, final int start, final float[] floats) {
    for (int pixel = FIRST_FLOAT_PIXEL; pixel < DESCRIPTOR_PIXELS; pixel++) {
      writeFloatPixel(strip, start, pixel, floats);
    }
  }

  private static void writeFloatPixel(final byte[] strip, final int start, final int pixel, final float[] floats) {
    final int index = (pixel - FIRST_FLOAT_PIXEL) / PIXELS_PER_FLOAT;
    if (index >= DESCRIPTOR_FLOATS) {
      writePixel(strip, start + pixel, 0, 0, 0);
      return;
    }

    final int bits = Float.floatToRawIntBits(floats[index]);
    if (pixel % PIXELS_PER_FLOAT == 0) {
      writePixel(strip, start + pixel, bits & BYTE_MASK, (bits >> SECOND_BYTE_SHIFT) & BYTE_MASK, (bits >> THIRD_BYTE_SHIFT) & BYTE_MASK);
      return;
    }

    writePixel(strip, start + pixel, bits >>> HIGH_BYTE_SHIFT, 0, 0);
  }

  // one block along a map's right edge, by facing: east, south, west, north
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

  // a column-major 4x4 matrix times a vector, the first three components
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

  private static void writePixel(final byte[] strip, final int pixel, final int red, final int green, final int blue) {
    final int at = pixel * CHANNELS;
    strip[at + RED_OFFSET] = (byte) red;
    strip[at + GREEN_OFFSET] = (byte) green;
    strip[at + BLUE_OFFSET] = (byte) blue;
    strip[at + ALPHA_OFFSET] = (byte) OPAQUE;
  }
}
