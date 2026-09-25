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
package me.brandonli.mcav.media.mcv2.encode;

import com.google.common.base.Preconditions;
import java.util.Arrays;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A node of the block tree an encoder builds: either a leaf carrying one record, or a split into four children in
 * the order top left, top right, bottom left, bottom right.
 *
 * <p>A leaf's record is its logical record as the wide-index form stores it; a pattern palette leaf carries its
 * endpoints and selector word inline, and the serializer decides which parts the frame's tables name instead.
 * Instances are immutable.
 */
public final class TreeNode {

  private static final TreeNode SKIP = new TreeNode(Mcv2Format.MODE_SKIP, 0, new byte[0], null);

  private final int mode;
  private final int q;
  private final byte[] record;
  private final TreeNode@Nullable[] children;

  private TreeNode(final int mode, final int q, final byte[] record, final TreeNode@Nullable[] children) {
    this.mode = mode;
    this.q = q;
    this.record = record;
    this.children = children;
  }

  /**
   * Gets the skipped leaf.
   *
   * @return a leaf of mode 0 with no record
   */
  public static TreeNode skip() {
    return SKIP;
  }

  /**
   * Creates a leaf.
   *
   * @param mode   the leaf mode
   * @param q      the quantizer, 0 to 7
   * @param record the record bytes, which are copied
   * @return the leaf
   */
  public static TreeNode leaf(final int mode, final int q, final byte[] record) {
    Preconditions.checkNotNull(record, "Record must not be null");
    Preconditions.checkArgument(mode != Mcv2Format.MODE_SPLIT && mode >= 0 && mode < 32, "Invalid leaf mode %s", mode);
    Preconditions.checkArgument(q >= 0 && q <= 7, "Invalid quantizer %s", q);
    return new TreeNode(mode, q, record.clone(), null);
  }

  /**
   * Creates a split.
   *
   * @param topLeft     the top left child
   * @param topRight    the top right child
   * @param bottomLeft  the bottom left child
   * @param bottomRight the bottom right child
   * @return the split
   */
  public static TreeNode split(final TreeNode topLeft, final TreeNode topRight, final TreeNode bottomLeft, final TreeNode bottomRight) {
    Preconditions.checkNotNull(topLeft, "Children must not be null");
    Preconditions.checkNotNull(topRight, "Children must not be null");
    Preconditions.checkNotNull(bottomLeft, "Children must not be null");
    Preconditions.checkNotNull(bottomRight, "Children must not be null");
    return new TreeNode(Mcv2Format.MODE_SPLIT, 0, new byte[0], new TreeNode[] { topLeft, topRight, bottomLeft, bottomRight });
  }

  /**
   * Gets the mode.
   *
   * @return the mode, {@link Mcv2Format#MODE_SPLIT} for a split
   */
  public int getMode() {
    return this.mode;
  }

  /**
   * Gets the quantizer.
   *
   * @return the quantizer, 0 to 7
   */
  public int getQ() {
    return this.q;
  }

  /**
   * Checks whether this node is a split.
   *
   * @return true for a split
   */
  public boolean isSplit() {
    return this.children != null;
  }

  /**
   * Gets a copy of the record.
   *
   * @return the record bytes, empty for a split or skip
   */
  public byte[] getRecord() {
    return this.record.clone();
  }

  byte[] record() {
    return this.record;
  }

  /**
   * Gets one child of a split.
   *
   * @param index 0 to 3, top left, top right, bottom left, bottom right
   * @return the child
   * @throws IllegalStateException if this node is a leaf
   */
  public TreeNode getChild(final int index) {
    final TreeNode[] nodes = this.children;
    if (nodes == null) {
      throw new IllegalStateException("A leaf has no children");
    }
    return nodes[index];
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (!(other instanceof final TreeNode node)) {
      return false;
    }
    return (
      this.mode == node.mode && this.q == node.q && Arrays.equals(this.record, node.record) && Arrays.equals(this.children, node.children)
    );
  }

  @Override
  public int hashCode() {
    return 31 * (31 * (31 * this.mode + this.q) + Arrays.hashCode(this.record)) + Arrays.hashCode(this.children);
  }

  @Override
  public String toString() {
    return this.isSplit() ? "split" + Arrays.toString(this.children) : "leaf(" + this.mode + "," + this.q + "," + this.record.length + "B)";
  }
}
