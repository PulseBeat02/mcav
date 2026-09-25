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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.media.mcv2.encode.FrameWriter;
import me.brandonli.mcav.media.mcv2.encode.TreeNode;

/**
 * Builds small valid MCV2 frames with the serializer, and corrupts single fields of them, for the tests of the parser,
 * the decoder and the transport.
 */
public final class Mcv2Frames {

  /** Every stored and derived feature enabled. */
  public static final FrameWriter.Options DERIVED = FrameWriter.Options.production(false);

  /** The derived form with RGB565 endpoints allowed. */
  public static final FrameWriter.Options DERIVED_565 = FrameWriter.Options.production(true);

  /** The derived form without packed symbols, a two-level walk or tables. */
  public static final FrameWriter.Options DERIVED_PLAIN = new FrameWriter.Options(
    false,
    false,
    false,
    true,
    true,
    false,
    false,
    false,
    false,
    false
  );

  /** Short stored descriptors with sparse children, immediate motion and the derived directory. */
  public static final FrameWriter.Options SHORT = new FrameWriter.Options(true, true, true, true, false, false, false, false, false, false);

  /** Short stored descriptors, ordinary child quartets and the plain directory. */
  public static final FrameWriter.Options SHORT_PLAIN = new FrameWriter.Options(
    true,
    false,
    false,
    false,
    false,
    false,
    false,
    false,
    false,
    false
  );

  /** Wide stored descriptors with immediate motion. */
  public static final FrameWriter.Options WIDE_IMMEDIATE = new FrameWriter.Options(
    false,
    false,
    true,
    false,
    false,
    false,
    false,
    false,
    false,
    false
  );

  /** Wide stored descriptors with the derived directory. */
  public static final FrameWriter.Options WIDE_DIRECTORY = new FrameWriter.Options(
    false,
    false,
    false,
    true,
    false,
    false,
    false,
    false,
    false,
    false
  );

  private Mcv2Frames() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * A solid leaf.
   *
   * @param r red
   * @param g green
   * @param b blue
   * @return the leaf
   */
  public static TreeNode solid(final int r, final int g, final int b) {
    return TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { (byte) r, (byte) g, (byte) b });
  }

  /**
   * A local motion leaf.
   *
   * @param dx the horizontal delta in half pixels
   * @param dy the vertical delta in half pixels
   * @return the leaf
   */
  public static TreeNode motion(final int dx, final int dy) {
    return TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { (byte) dx, (byte) dy });
  }

  /**
   * A leaf with a record of the right length whose bytes are {@code seed, seed + 1, ...}.
   *
   * @param mode the mode, 0 to 15
   * @param q    the quantizer
   * @param size the leaf size
   * @param seed the first byte
   * @return the leaf
   */
  public static TreeNode leaf(final int mode, final int q, final int size, final int seed) {
    final byte[] record = new byte[Mcv2Format.recordSize(mode, size)];
    for (int i = 0; i < record.length; i++) {
      record[i] = (byte) (seed + i);
    }
    return TreeNode.leaf(mode, q, record);
  }

  /**
   * A pattern leaf.
   *
   * @param size        the leaf size
   * @param endpoints   the six endpoint bytes
   * @param orientation 0 or 1
   * @param axisByte    the value of every axis byte
   * @return the leaf
   */
  public static TreeNode pattern(final int size, final byte[] endpoints, final int orientation, final int axisByte) {
    final byte[] record = new byte[7 + size / 8];
    System.arraycopy(endpoints, 0, record, 0, 6);
    record[6] = (byte) orientation;
    Arrays.fill(record, 7, record.length, (byte) axisByte);
    return TreeNode.leaf(Mcv2Format.MODE_PATTERN, 0, record);
  }

  /**
   * A split of four equal children.
   *
   * @param child the child
   * @return the split
   */
  public static TreeNode split(final TreeNode child) {
    return TreeNode.split(child, child, child, child);
  }

  /**
   * Serializes a keyframe.
   *
   * @param width   the width
   * @param height  the height
   * @param options the index form
   * @param roots   the roots, in raster order
   * @return the frame
   */
  public static byte[] keyframe(final int width, final int height, final FrameWriter.Options options, final TreeNode... roots) {
    return FrameWriter.write(width, height, 0, 0, true, 0, 0, List.of(roots), options);
  }

  /**
   * Serializes a P frame with id 1 predicting from frame 0.
   *
   * @param width   the width
   * @param height  the height
   * @param motionX the global horizontal motion
   * @param motionY the global vertical motion
   * @param options the index form
   * @param roots   the roots, in raster order
   * @return the frame
   */
  public static byte[] predicted(
    final int width,
    final int height,
    final int motionX,
    final int motionY,
    final FrameWriter.Options options,
    final TreeNode... roots
  ) {
    return FrameWriter.write(width, height, 1, 0, false, motionX, motionY, List.of(roots), options);
  }

  /**
   * Repeats a root.
   *
   * @param root  the root
   * @param count how many
   * @return the roots
   */
  public static TreeNode[] repeat(final TreeNode root, final int count) {
    final List<TreeNode> roots = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      roots.add(root);
    }
    return roots.toArray(new TreeNode[0]);
  }

  /**
   * Copies a frame with one byte replaced.
   *
   * @param frame  the frame
   * @param offset the offset
   * @param value  the new value
   * @return the copy
   */
  public static byte[] withByte(final byte[] frame, final int offset, final int value) {
    final byte[] copy = frame.clone();
    copy[offset] = (byte) value;
    return copy;
  }

  /**
   * Copies a frame with one little-endian 32-bit word replaced.
   *
   * @param frame  the frame
   * @param offset the offset
   * @param value  the new value
   * @return the copy
   */
  public static byte[] withWord(final byte[] frame, final int offset, final long value) {
    final byte[] copy = frame.clone();
    Mcv2Format.putU32(copy, offset, value);
    return copy;
  }

  /**
   * Copies a frame with one little-endian 16-bit word replaced.
   *
   * @param frame  the frame
   * @param offset the offset
   * @param value  the new value
   * @return the copy
   */
  public static byte[] withHalf(final byte[] frame, final int offset, final int value) {
    final byte[] copy = frame.clone();
    Mcv2Format.putU16(copy, offset, value);
    return copy;
  }

  /**
   * Copies a frame with its header flags replaced.
   *
   * @param frame the frame
   * @param flags the new flags
   * @return the copy
   */
  public static byte[] withFlags(final byte[] frame, final int flags) {
    return withWord(frame, 4, Mcv2Format.CONFIGURATION | ((long) flags << 16));
  }

  /**
   * Copies a frame with bytes appended and the total length updated to match.
   *
   * @param frame the frame
   * @param extra the bytes to append
   * @return the copy
   */
  public static byte[] appended(final byte[] frame, final byte... extra) {
    final byte[] copy = Arrays.copyOf(frame, frame.length + extra.length);
    System.arraycopy(extra, 0, copy, frame.length, extra.length);
    Mcv2Format.putU32(copy, 32, copy.length);
    return copy;
  }

  /**
   * Copies a frame with bytes inserted at an offset, updating the total length and, optionally, the payload offset.
   *
   * @param frame        the frame
   * @param offset       where to insert
   * @param movePayload  whether the payload start moves with the insertion
   * @param extra        the bytes to insert
   * @return the copy
   */
  public static byte[] inserted(final byte[] frame, final int offset, final boolean movePayload, final byte... extra) {
    final byte[] copy = new byte[frame.length + extra.length];
    System.arraycopy(frame, 0, copy, 0, offset);
    System.arraycopy(extra, 0, copy, offset, extra.length);
    System.arraycopy(frame, offset, copy, offset + extra.length, frame.length - offset);
    Mcv2Format.putU32(copy, 32, copy.length);
    if (movePayload) {
      Mcv2Format.putU32(copy, 28, Mcv2Format.u32(copy, 28) + extra.length);
    }
    return copy;
  }
}
