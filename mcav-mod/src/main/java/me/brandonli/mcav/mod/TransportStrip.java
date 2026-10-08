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

  private static final int OPAQUE = 255;

  private static final int SYMBOL_BITS = 6;

  private static final int SYMBOLS_PER_PIXEL = 4;

  /** A page's stream id and page number in its header: bits 64 to 95 and 128 to 143 of its symbol stream. */
  private static final int STREAM_BIT = 64;

  private static final int NUMBER_BIT = 128;

  private static final int HALF_WORD = 16;

  /** The descriptor: "MCV", a version byte, then 28 floats, each in two pixels: three low bytes, then the high one. */
  private static final int[] SIGNATURE = { 0x4D, 0x43, 0x56 };

  private static final int VERSION = 0xA1;

  private static final int FLOATS = 28;

  private static final int PROJECTION = 12;

  private static final int MATRIX = 16;

  private static final int ANCHOR_INDEX_STRIDE = 64;

  private TransportStrip() {}

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
      final int symbol = Math.max(Mcv2Maps.symbol(colours, at / SYMBOL_BITS), 0);
      value |= ((symbol >> (at % SYMBOL_BITS)) & 1) << index;
    }
    return value;
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
        word |= Math.max(Mcv2Maps.symbol(page, pixel * SYMBOLS_PER_PIXEL + index), 0) << (SYMBOL_BITS * index);
      }
      put(strip, first + pixel, word & 0xFF, (word >> 8) & 0xFF, word >> HALF_WORD);
    }
  }

  private static void writeDescriptor(
    final byte[] strip,
    final int start,
    final StripAnchor anchor,
    final float[] viewRotation,
    final float[] projection
  ) {
    final float[] corner = transform(viewRotation, anchor.cornerX(), anchor.cornerY(), anchor.cornerZ(), 1);
    final float[] right = transform(viewRotation, rightX(anchor.facing()), 0, rightZ(anchor.facing()), 0);
    final float[] down = transform(viewRotation, 0, -1, 0, 0);
    final float[] floats = new float[FLOATS];
    for (int axis = 0; axis < 3; axis++) {
      floats[axis] = corner[axis] - anchor.column() * right[axis] - anchor.row() * down[axis];
      floats[4 + axis] = right[axis];
      floats[8 + axis] = down[axis];
    }
    floats[3] = anchor.columns();
    floats[7] = anchor.rows();
    floats[11] = anchor.column() * ANCHOR_INDEX_STRIDE + anchor.row();
    System.arraycopy(projection, 0, floats, PROJECTION, MATRIX);
    put(strip, start, SIGNATURE[0], SIGNATURE[1], SIGNATURE[2]);
    put(strip, start + 1, VERSION, 0, 0);
    for (int pixel = 2; pixel < DESCRIPTOR_PIXELS; pixel++) {
      final int index = (pixel - 2) / 2;
      if (index >= FLOATS) {
        put(strip, start + pixel, 0, 0, 0);
      } else if (pixel % 2 == 0) {
        final int bits = Float.floatToRawIntBits(floats[index]);
        put(strip, start + pixel, bits & 0xFF, (bits >> 8) & 0xFF, (bits >> HALF_WORD) & 0xFF);
      } else {
        put(strip, start + pixel, Float.floatToRawIntBits(floats[index]) >>> 24, 0, 0);
      }
    }
  }

  /** The world direction one block along a map's right edge, by facing: east, south, west, north. */
  private static float rightX(final int facing) {
    return facing == 0 ? 1 : facing == 2 ? -1 : 0;
  }

  private static float rightZ(final int facing) {
    return facing == 1 ? 1 : facing == 3 ? -1 : 0;
  }

  /** A column-major 4x4 matrix times a vector, the first three components. */
  static float[] transform(final float[] matrix, final float vectorX, final float vectorY, final float vectorZ, final float vectorW) {
    final float[] out = new float[3];
    for (int row = 0; row < 3; row++) {
      out[row] = matrix[row] * vectorX + matrix[4 + row] * vectorY + matrix[8 + row] * vectorZ + matrix[12 + row] * vectorW;
    }
    return out;
  }

  private static void put(final byte[] strip, final int pixel, final int red, final int green, final int blue) {
    final int at = pixel * CHANNELS;
    strip[at] = (byte) red;
    strip[at + 1] = (byte) green;
    strip[at + 2] = (byte) blue;
    strip[at + 3] = (byte) OPAQUE;
  }
}
