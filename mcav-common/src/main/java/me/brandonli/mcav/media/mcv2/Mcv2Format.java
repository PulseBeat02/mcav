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
 * The constants of the MCV2 bitstream and the small arithmetic every reader and writer shares.
 *
 * <p>The normative definition is the Python reference decoder of the gpu-codec research repository at commit
 * {@code 85445433aeb9f8a35a5ce528d47d8829976d1401} ({@code mcvideo/format.py}, {@code v2.py}, {@code compact.py},
 * {@code pattern.py}, {@code decoder.py}); {@code docs/mcv2-format.md} writes it down. All multi-byte integers are
 * little-endian.
 */
public final class Mcv2Format {

  /** Bytes of the frame header: twelve unsigned 32-bit words. */
  public static final int HEADER_BYTES = 48;

  /** The largest frame, in bytes; offsets are 24-bit. */
  public static final int MAX_FRAME_BYTES = (1 << 24) - 1;

  /** The largest width or height, in pixels. */
  public static final int MAX_DIMENSION = 4096;

  /** The bytes {@code MCV2} as a little-endian word. */
  public static final int MAGIC = 0x3256434D;

  /** The bytes {@code MCV1} as a little-endian word, the magic of the unsupported first version. */
  public static final int MCV1_MAGIC = 0x3156434D;

  /** Version 2 in bits 0..7 and the root block size log2 = 5 in bits 8..15 of the configuration word. */
  public static final int CONFIGURATION = 0x0502;

  /** The size of a root block, in pixels. */
  public static final int ROOT_SIZE = 32;

  /** The channels of a pixel: red, green and blue, one byte each, in that order. */
  public static final int CHANNELS = 3;

  /** The largest value of a channel. */
  public static final int MAX_CHANNEL = 255;

  /** The smallest block, in pixels: a leaf is 8, 16 or 32 pixels square. */
  public static final int SMALLEST_BLOCK = 8;

  /** The block sizes a leaf may have: 8, 16 and 32 pixels. */
  public static final int BLOCK_SIZES = 3;

  /** The widest node grid a leaf's record may carry, 8 by 8. */
  public static final int MAX_GRID = 8;

  /** The node grid widths a record may carry: 1, 2, 4 and 8. */
  public static final int GRID_WIDTHS = 4;

  /** A motion vector's bytes: signed x and y, in half pixels. */
  public static final int MOTION_BYTES = 2;

  /** The colours of a two-colour palette or pattern. */
  public static final int PALETTE_COLORS = 2;

  /** The chroma planes of the reduced-chroma and compact records, Co and Cg. */
  public static final int CHROMA_PLANES = 2;

  /** The largest quantizer, the three bits above a descriptor's mode. */
  public static final int MAX_QUANTIZER = 7;

  /** The two delta widths, a byte each, that open a two-level walk region. */
  public static final int WALK_WIDTHS_BYTES = 2;

  /** A walk checkpoint stored whole: its payload cursor and split prefix, a half-word each. */
  public static final int WALK_PAIR_BYTES = 2 * Short.BYTES;

  /** The largest half-word: the limit of 16-bit counts, cursors and offsets. */
  public static final int HALF_WORD = 0xFFFF;

  /** The bits of a half-word. */
  public static final int HALF_WORD_BITS = 16;

  /** A short descriptor: a 16-bit offset and the mode and quantizer byte. */
  public static final int SHORT_DESCRIPTOR_BYTES = 3;

  /** A sparse split's child mask with all four children, which a sparse split never has. */
  public static final int ALL_QUARTERS = 15;

  /** An RGB565 colour. */
  public static final int RGB565_BYTES = 2;

  /** An endpoint pair of full colours. */
  public static final int ENDPOINT_PAIR_BYTES = PALETTE_COLORS * CHANNELS;

  /** An endpoint pair of RGB565 colours. */
  public static final int ENDPOINT_565_PAIR_BYTES = PALETTE_COLORS * RGB565_BYTES;

  /** Bytes of a header word, a directory mask or checkpoint, and a wide descriptor. */
  public static final int WORD_BYTES = 4;

  /** A stored directory group: its root mask and the pointer to its first root descriptor. */
  public static final int STORED_GROUP_BYTES = 2 * WORD_BYTES;

  /** The largest unsigned 32-bit value: the largest frame and stream id. */
  public static final long MAX_U32 = 0xFFFFFFFFL;

  /** Frame ids wrap: an id is newer than another when it is less than half the id space ahead of it. */
  private static final long HALF_ID_SPACE = 1L << (Integer.SIZE - 1);

  /** The header word holding the version and block size in its low and the flags in its high half-word. */
  public static final int CONFIGURATION_OFFSET = 4;

  /** The header word holding the width in its low and the height in its high half-word. */
  public static final int DIMENSIONS_OFFSET = 8;

  /** The header word holding the frame's id. */
  public static final int FRAME_ID_OFFSET = 12;

  /** The header word holding the id of the frame a P frame predicts from; a keyframe's own. */
  public static final int REFERENCE_ID_OFFSET = 16;

  /** The header word holding the global vector: signed half pixels, x in the low and y in the high half-word. */
  public static final int MOTION_OFFSET = 20;

  /** The header word holding the number of root blocks. */
  public static final int ROOT_COUNT_OFFSET = 24;

  /** The header word holding the payload's byte offset. */
  public static final int PAYLOAD_START_OFFSET = 28;

  /** The header word holding the frame's length in bytes. */
  public static final int TOTAL_OFFSET = 32;

  /** The header word holding a keyframe's default colour as blue, green and red bytes. */
  public static final int DEFAULT_COLOR_OFFSET = 36;

  /** The first of the header's two reserved words, which are zero. */
  public static final int RESERVED_OFFSET = 40;

  /** Roots a directory group's mask covers, one bit each. */
  public static final int GROUP_ROOTS = 32;

  /** The quarters a split node has. */
  public static final int QUARTERS = 4;

  /** A descriptor's record offset: its low 24 bits. */
  public static final int OFFSET_MASK = 0xFFFFFF;

  /** The first bit of a descriptor's mode, which is five bits wide. */
  public static final int MODE_SHIFT = 24;

  /** A mode's five bits. */
  public static final int MODE_MASK = 31;

  /** The first bit of a descriptor's quantizer, its top three bits. */
  public static final int QUANTIZER_SHIFT = 29;

  /** The first bit of the quantizer in a derived-form symbol, whose low five bits are the mode. */
  public static final int SYMBOL_QUANTIZER_SHIFT = 5;

  /** Frame flag: an independent frame. */
  public static final int KEYFRAME = 1;

  /** Frame flag: the root index is a sparse directory. */
  public static final int SPARSE = 2;

  /** Frame flag: skipped keyframe blocks take the header colour. */
  public static final int DEFAULT_SOLID = 4;

  /** Frame flag: three-byte descriptors with a 16-bit offset. */
  public static final int SHORT_INDEX = 8;

  /** Frame flag: the root directory stores masks and one checkpoint per eight groups. */
  public static final int DERIVED_DIRECTORY = 16;

  /** Frame flag: level-ordered one-byte descriptors, every address recovered. */
  public static final int DERIVED_OFFSETS = 32;

  /** Frame flag: descriptors index a per-frame table of (mode, quantizer) pairs. */
  public static final int PACKED_SYMBOLS = 64;

  /** Frame flag: walk checkpoints are a coarse plane plus anchor-relative deltas. */
  public static final int TWO_LEVEL_WALK = 128;

  /** Frame flag of the reverted round 15: a per-frame motion vector table. Not supported. */
  public static final int MOTION_TABLE = 256;

  /** Frame flag: pattern palettes name their endpoints in a per-frame table. */
  public static final int ENDPOINT_TABLE = 512;

  /** Frame flag: 8-pixel pattern palettes name their selector word in a per-frame table. */
  public static final int SELECTOR_TABLE_8 = 1024;

  /** Frame flag: 16-pixel pattern palettes name their selector word in a per-frame table. */
  public static final int SELECTOR_TABLE_16 = 2048;

  /** Frame flag: 32-pixel pattern palettes name their selector word in a per-frame table. */
  public static final int SELECTOR_TABLE_32 = 4096;

  /** All three selector table flags. */
  public static final int SELECTOR_TABLE = SELECTOR_TABLE_8 | SELECTOR_TABLE_16 | SELECTOR_TABLE_32;

  /** Frame flag: endpoint table entries are two RGB565 colours. */
  public static final int ENDPOINT_565 = 8192;

  /** Every flag bit the format defines. */
  public static final int ALL_FLAGS = 16383;

  /** Leaf: global-motion prediction on P frames, the header colour on keyframes. */
  public static final int MODE_SKIP = 0;

  /** Leaf: signed 8-bit local motion. */
  public static final int MODE_MOTION = 1;

  /** Leaf: one RGB colour. */
  public static final int MODE_SOLID = 2;

  /** Leaf: two RGB endpoints and one selector bit per pixel. */
  public static final int MODE_PALETTE = 3;

  /** Leaf: RGB intra grids of 1, 2, 4 and 8 samples per axis are modes 4 to 7. */
  public static final int MODE_INTRA = 4;

  /** Leaf: signed YCoCg residual grids with local motion are modes 8 to 11. */
  public static final int MODE_RESIDUAL = 8;

  /** Leaf: intra 4x4 luma and one chroma pair. */
  public static final int MODE_INTRA_Y4C1 = 12;

  /** Leaf: residual 4x4 luma and one chroma pair. */
  public static final int MODE_RESIDUAL_Y4C1 = 13;

  /** Leaf: intra 8x8 luma and 2x2 chroma. */
  public static final int MODE_INTRA_Y8C2 = 14;

  /** Leaf: residual 8x8 luma and 2x2 chroma. */
  public static final int MODE_RESIDUAL_Y8C2 = 15;

  /** Node: four children follow, TL, TR, BL, BR. */
  public static final int MODE_SPLIT = 16;

  /** Leaf: a compact temporal residual record. */
  public static final int MODE_COMPACT = 17;

  /** Leaf: a two-colour palette whose selectors repeat along one axis. */
  public static final int MODE_PATTERN = 18;

  /** Node: a child mask byte then only the active child descriptors (short index only). */
  public static final int MODE_SPARSE_SPLIT = 19;

  /** Leaf: a motion record carried in the offset field (stored index forms only). */
  public static final int MODE_IMMEDIATE_MOTION = 20;

  /** Leaf of the reverted round 3: coarse palette, one selector per 2x2 pixels. Not supported. */
  public static final int MODE_COARSE_PALETTE_2 = 21;

  /** Leaf of the reverted round 3: coarse palette, one selector per 4x4 pixels. Not supported. */
  public static final int MODE_COARSE_PALETTE_4 = 22;

  /** Leaf of the reverted round 15: an index into the motion table. Not supported. */
  public static final int MODE_INDEXED_MOTION = 23;

  /** Root groups between stored directory checkpoints. */
  public static final int CHECKPOINT_GROUPS = 8;

  /** Descriptors between walk checkpoints of the derived form. */
  public static final int WALK_SPAN = 8;

  /** Walk checkpoints per absolute pair of the two-level walk plane. */
  public static final int WALK_STRIDE = 4;

  /** Bits of an anchor-relative walk delta entry. */
  public static final int DELTA_BITS = 16;

  /** Distinct (mode, quantizer) pairs a packed-symbol frame may name. */
  public static final int MAX_SYMBOLS = 32;

  /** Entries an endpoint or selector table may hold; the count is one byte. */
  public static final int MAX_TABLE_ENTRIES = 255;

  private Mcv2Format() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads an unsigned little-endian 16-bit integer.
   *
   * @param data   the bytes
   * @param offset where to read; both bytes must be inside the bytes
   * @return the value, 0 to 65535
   */
  public static int u16(final byte[] data, final int offset) {
    return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
  }

  /**
   * Reads an unsigned little-endian 32-bit integer.
   *
   * @param data   the bytes
   * @param offset where to read; all four bytes must be inside the bytes
   * @return the value, 0 to 4294967295
   */
  public static long u32(final byte[] data, final int offset) {
    return (
      (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24)
    );
  }

  /**
   * Writes a little-endian 16-bit integer.
   *
   * @param data   the bytes
   * @param offset where to write
   * @param value  the value; only the low 16 bits are written
   */
  public static void putU16(final byte[] data, final int offset, final int value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
  }

  /**
   * Writes a little-endian 32-bit integer.
   *
   * @param data   the bytes
   * @param offset where to write
   * @param value  the value; only the low 32 bits are written
   */
  public static void putU32(final byte[] data, final int offset, final long value) {
    data[offset] = (byte) value;
    data[offset + 1] = (byte) (value >>> 8);
    data[offset + 2] = (byte) (value >>> 16);
    data[offset + 3] = (byte) (value >>> 24);
  }

  /**
   * Checks whether a mode is a temporal grid residual, which carries local motion and a quantizer.
   *
   * @param mode the mode
   * @return true for modes 8 to 11, 13 and 15
   */
  public static boolean isResidual(final int mode) {
    return (mode >= MODE_RESIDUAL && mode < MODE_INTRA_Y4C1) || mode == MODE_RESIDUAL_Y4C1 || mode == MODE_RESIDUAL_Y8C2;
  }

  /**
   * The fixed record length of a leaf of modes 0 to 15, which depends only on the mode and the leaf size.
   *
   * @param mode the mode, 0 to 15
   * @param size the leaf size, 8, 16 or 32
   * @return the record length in bytes
   */
  public static int recordSize(final int mode, final int size) {
    if (mode == MODE_SKIP) {
      return 0;
    }
    if (mode == MODE_MOTION) {
      return MOTION_BYTES;
    }
    if (mode == MODE_SOLID) {
      return CHANNELS;
    }
    if (mode == MODE_PALETTE) {
      return PALETTE_COLORS * CHANNELS + (size * size) / Byte.SIZE;
    }
    if (mode < MODE_RESIDUAL) {
      final int grid = 1 << (mode - MODE_INTRA);
      return CHANNELS * grid * grid;
    }
    if (mode < MODE_INTRA_Y4C1) {
      final int grid = 1 << (mode - MODE_RESIDUAL);
      return MOTION_BYTES + CHANNELS * grid * grid;
    }
    final int luma = lumaGrid(mode);
    final int chroma = chromaGrid(mode);
    return luma * luma + CHROMA_PLANES * chroma * chroma + (isResidual(mode) ? MOTION_BYTES : 0);
  }

  /**
   * Checks whether a frame id follows another: ids wrap modulo 2^32, and an id follows when {@code 0 < (id - last) mod
   * 2^32 < 2^31}.
   *
   * @param id   the new id
   * @param last the last id
   * @return true if the new id follows the last one
   */
  public static boolean follows(final long id, final long last) {
    final long distance = (id - last) & MAX_U32;
    return distance != 0 && distance < HALF_ID_SPACE;
  }

  /**
   * Checks whether a size is a block size: 8, 16 or 32 pixels.
   *
   * @param size the size
   * @return true for a block size
   */
  public static boolean isBlockSize(final int size) {
    return size == SMALLEST_BLOCK || size == 2 * SMALLEST_BLOCK || size == ROOT_SIZE;
  }

  /**
   * The index of a block size among the three: 0 for 8, 1 for 16 and 2 for 32 pixels.
   *
   * @param size the block size
   * @return the index
   */
  public static int sizeIndex(final int size) {
    return Integer.numberOfTrailingZeros(size) - Integer.numberOfTrailingZeros(SMALLEST_BLOCK);
  }

  /**
   * The luma grid width of a reduced-chroma mode.
   *
   * @param mode a mode from 12 to 15
   * @return 4 or 8
   */
  public static int lumaGrid(final int mode) {
    return mode < MODE_INTRA_Y8C2 ? MAX_GRID / 2 : MAX_GRID;
  }

  /**
   * The chroma grid width of a reduced-chroma mode.
   *
   * @param mode a mode from 12 to 15
   * @return 1 or 2
   */
  public static int chromaGrid(final int mode) {
    return mode < MODE_INTRA_Y8C2 ? 1 : 2;
  }

  /**
   * The length of a pattern palette record.
   *
   * @param size              the leaf size, 8, 16 or 32
   * @param indexedEndpoints  whether the frame names endpoints in its table, leaving a one-byte index in the record
   * @param tabledSelectors   whether the frame names this size's selector words in its table
   * @return the record length in bytes
   */
  public static int patternSize(final int size, final boolean indexedEndpoints, final boolean tabledSelectors) {
    final int ends = indexedEndpoints ? 1 : PALETTE_COLORS * CHANNELS;
    final int rest = tabledSelectors ? 1 : 1 + size / Byte.SIZE;
    return ends + rest;
  }

  /**
   * The bits of one packed descriptor for a symbol table of {@code count} entries.
   *
   * @param count the number of distinct (mode, quantizer) pairs, 1 to 32
   * @return the symbol width, at least 1
   */
  public static int symbolWidth(final int count) {
    final int values = Math.max(1, count) - 1;
    return Math.max(1, 32 - Integer.numberOfLeadingZeros(values));
  }

  /**
   * The length of the walk checkpoint region of the derived form.
   *
   * @param walkpoints the number of checkpoints
   * @param twoLevel   whether the region is a coarse plane plus deltas
   * @return the length in bytes
   */
  public static int walkBytes(final int walkpoints, final boolean twoLevel) {
    if (!twoLevel) {
      return walkpoints * WORD_BYTES;
    }
    final int coarse = (walkpoints + WALK_STRIDE - 1) / WALK_STRIDE;
    return WALK_WIDTHS_BYTES + coarse * WORD_BYTES + (walkpoints - coarse) * Short.BYTES;
  }

  /**
   * Packs a colour into RGB565, truncating each channel, as the encoder's quantizer does.
   *
   * @param red   the red channel, 0 to 255
   * @param green the green channel, 0 to 255
   * @param blue  the blue channel, 0 to 255
   * @return the 16-bit colour
   */
  public static int pack565(final int red, final int green, final int blue) {
    return ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3);
  }

  /**
   * Expands an RGB565 colour exactly as the encoder's quantizer and the shader do.
   *
   * @param low  the low byte
   * @param high the high byte
   * @return the colour as {@code 0xRRGGBB}
   */
  public static int unpack565(final int low, final int high) {
    final int value = (low & 0xFF) | ((high & 0xFF) << 8);
    final int r = (value >> 11) & 31;
    final int g = (value >> 5) & 63;
    final int b = value & 31;
    return (((r << 3) | (r >> 2)) << 16) | (((g << 2) | (g >> 4)) << 8) | ((b << 3) | (b >> 2));
  }

  /**
   * Sign-extends the low bits of a value.
   *
   * @param value the value
   * @param width the number of bits, 1 to 31
   * @return the signed value
   */
  public static int signed(final int value, final int width) {
    final int shift = 32 - width;
    return (value << shift) >> shift;
  }
}
