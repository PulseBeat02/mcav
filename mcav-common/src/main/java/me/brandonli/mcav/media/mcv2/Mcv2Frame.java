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

import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A completely validated MCV2 frame: its header fields and every leaf of its block tree, with the address each leaf's
 * record starts at. Frames are only created by {@link FrameParser}, which validates the whole frame first, so a frame
 * that exists is canonical and every record lies inside its bytes.
 *
 * <p>Leaves are listed in the order the parser meets them. For each leaf the frame stores its top left corner, its
 * size (8, 16 or 32), its mode and quantizer, and its record offset. An immediate motion leaf stores its two motion
 * bytes, low byte first, in place of an offset, and a skipped leaf stores zero.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Mcv2Frame {

  static final int LEAF_INTS = 6;

  private final byte[] data;
  private final int width;
  private final int height;
  private final long frameId;
  private final long referenceId;
  private final int flags;
  private final int globalX;
  private final int globalY;
  private final int payloadStart;
  private final int defaultColor;
  private final int[] leaves;
  private final byte@Nullable[] endpointTable;
  private final byte@Nullable[][] selectorTables;

  Mcv2Frame(
    final byte[] data,
    final Header header,
    final int[] leaves,
    final byte@Nullable[] endpointTable,
    final byte@Nullable[][] selectorTables
  ) {
    this.data = data;
    this.width = header.width();
    this.height = header.height();
    this.frameId = header.frameId();
    this.referenceId = header.referenceId();
    this.flags = header.flags();
    this.globalX = header.globalX();
    this.globalY = header.globalY();
    this.payloadStart = header.payloadStart();
    this.defaultColor = header.defaultColor();
    this.leaves = leaves;
    this.endpointTable = endpointTable;
    this.selectorTables = selectorTables;
  }

  /**
   * The validated header fields of a frame.
   *
   * @param width        the width in pixels
   * @param height       the height in pixels
   * @param frameId      the unsigned 32-bit frame id
   * @param referenceId  the unsigned 32-bit id of the frame this one predicts from, its own id on a keyframe
   * @param flags        the flag bits
   * @param globalX      the frame-global horizontal motion, in half pixels
   * @param globalY      the frame-global vertical motion, in half pixels
   * @param payloadStart the offset of the first record byte
   * @param defaultColor the keyframe default colour as {@code 0xRRGGBB}
   */
  record Header(
    int width,
    int height,
    long frameId,
    long referenceId,
    int flags,
    int globalX,
    int globalY,
    int payloadStart,
    int defaultColor
  ) {}

  byte[] data() {
    return this.data;
  }

  int[] leafArray() {
    return this.leaves;
  }

  byte@Nullable[] endpointTable() {
    return this.endpointTable;
  }

  byte@Nullable[] selectorTable(final int size) {
    final byte[][] tables = this.selectorTables;
    return tables == null ? null : tables[Integer.numberOfTrailingZeros(size) - 3];
  }

  /**
   * Gets a copy of the frame's endpoint table, expanded to six-byte RGB pairs even when the frame stores RGB565.
   *
   * @return the table, or null when the frame has none
   */
  public byte@Nullable[] getEndpointTable() {
    final byte[] table = this.endpointTable;
    return table == null ? null : table.clone();
  }

  /**
   * Gets a copy of the frame's selector table for one leaf size.
   *
   * @param size the leaf size, 8, 16 or 32
   * @return the table of {@code 1 + size / 8}-byte words, or null when the frame names no words of that size
   */
  public byte@Nullable[] getSelectorTable(final int size) {
    Preconditions.checkArgument(size == 8 || size == 16 || size == 32, "Invalid leaf size %s", size);
    final byte[] table = this.selectorTable(size);
    return table == null ? null : table.clone();
  }

  /**
   * Gets a copy of the frame's bytes.
   *
   * @return the bytes of the frame
   */
  public byte[] getData() {
    return this.data.clone();
  }

  /**
   * Gets the frame's length in bytes.
   *
   * @return the length
   */
  public int getLength() {
    return this.data.length;
  }

  /**
   * Gets the width of the picture.
   *
   * @return the width in pixels
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the picture.
   *
   * @return the height in pixels
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets the frame id.
   *
   * @return the unsigned 32-bit frame id
   */
  public long getFrameId() {
    return this.frameId;
  }

  /**
   * Gets the id of the frame this one is predicted from. A keyframe names itself.
   *
   * @return the unsigned 32-bit reference id
   */
  public long getReferenceId() {
    return this.referenceId;
  }

  /**
   * Gets the flag bits of the header.
   *
   * @return the flags, 0 to 16383
   */
  public int getFlags() {
    return this.flags;
  }

  /**
   * Checks whether this frame is independent of any reference.
   *
   * @return true for a keyframe
   */
  public boolean isKeyframe() {
    return (this.flags & Mcv2Format.KEYFRAME) != 0;
  }

  /**
   * Gets the frame-global horizontal motion.
   *
   * @return the motion in half pixels
   */
  public int getGlobalX() {
    return this.globalX;
  }

  /**
   * Gets the frame-global vertical motion.
   *
   * @return the motion in half pixels
   */
  public int getGlobalY() {
    return this.globalY;
  }

  /**
   * Gets the offset where the records start, which is also the length of the header and index.
   *
   * @return the payload offset in bytes
   */
  public int getPayloadStart() {
    return this.payloadStart;
  }

  /**
   * Gets the colour skipped keyframe leaves take.
   *
   * @return the colour as {@code 0xRRGGBB}, zero when the frame has none
   */
  public int getDefaultColor() {
    return this.defaultColor;
  }

  /**
   * Gets the number of leaves of the block tree, including roots the index leaves out.
   *
   * @return the leaf count
   */
  public int getLeafCount() {
    return this.leaves.length / LEAF_INTS;
  }

  /**
   * Gets one leaf.
   *
   * @param index the leaf index
   * @return the leaf
   * @throws IndexOutOfBoundsException if the index is not a leaf
   */
  public Leaf getLeaf(final int index) {
    Preconditions.checkElementIndex(index, this.getLeafCount(), "Leaf index");
    final int base = index * LEAF_INTS;
    final int[] l = this.leaves;
    return new Leaf(l[base], l[base + 1], l[base + 2], l[base + 3], l[base + 4], l[base + 5]);
  }

  /**
   * One leaf of the block tree.
   *
   * @param x      the left edge in pixels
   * @param y      the top edge in pixels
   * @param size   the width and height, 8, 16 or 32
   * @param mode   the leaf mode
   * @param q      the quantizer, 0 to 7
   * @param offset the record offset, the motion bytes of an immediate motion leaf, or 0 for a skipped leaf
   */
  public record Leaf(int x, int y, int size, int mode, int q, int offset) {}
}
