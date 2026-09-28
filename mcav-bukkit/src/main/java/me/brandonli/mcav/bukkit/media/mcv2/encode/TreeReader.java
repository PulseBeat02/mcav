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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DEFAULT_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ENDPOINT_PAIR_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_IMMEDIATE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.recordSize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.bukkit.media.mcv2.PatternRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Rebuilds the block tree of a validated frame, like the reference's {@code repack.roots_from_frame}: every leaf keeps
 * its mode, quantizer and record in the wide form, immediate motion becomes an ordinary motion leaf, a pattern becomes
 * the full palette it stands for, and a skipped keyframe leaf becomes the default colour's solid leaf. Serializing the
 * result with the frame's own options reproduces the frame byte for byte.
 */
public final class TreeReader {

  private static final int KEY_X_SHIFT = 40;

  private static final int KEY_Y_SHIFT = 16;

  private TreeReader() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Rebuilds the roots of a frame.
   *
   * @param frame the frame
   * @return one root per 32-pixel block, in raster order
   * @throws Mcv2Exception never for a frame the parser accepted; declared because records are re-read
   */
  public static List<TreeNode> roots(final Mcv2Frame frame) throws Mcv2Exception {
    return roots(frame, Workers.SEQUENTIAL);
  }

  /**
   * Rebuilds the roots of a frame, the trees of its superblocks on the workers.
   *
   * @param frame   the frame
   * @param workers the workers, a superblock each
   * @return one root per 32-pixel block, in raster order
   * @throws Mcv2Exception never for a frame the parser accepted; declared because records are re-read
   */
  public static List<TreeNode> roots(final Mcv2Frame frame, final Workers workers) throws Mcv2Exception {
    final int count = frame.getLeafCount();
    final TreeNode[] nodes = new TreeNode[count];
    final boolean hasDefaultSolid = (frame.getFlags() & DEFAULT_SOLID) != 0;
    // the frame hands out copies of its bytes and tables, so each is taken once for all the leaves
    final byte[] data = frame.getData();
    final byte @Nullable [] endpoints = frame.getEndpointTable();
    final List<byte @Nullable []> selectors = new ArrayList<>(BLOCK_SIZES);
    for (int size = SMALLEST_BLOCK; size <= ROOT_SIZE; size *= 2) {
      selectors.add(frame.getSelectorTable(size));
    }
    for (int leafIndex = 0; leafIndex < count; leafIndex++) {
      nodes[leafIndex] = node(frame, data, frame.getLeaf(leafIndex), endpoints, selectors, hasDefaultSolid);
    }
    // the leaves grouped by superblock, each group in the frame's order: counted, then placed
    final int columns = (frame.getWidth() + ROOT_SIZE - 1) / ROOT_SIZE;
    final int superblocks = columns * ((frame.getHeight() + ROOT_SIZE - 1) / ROOT_SIZE);
    final int[] first = new int[superblocks + 1];
    for (int leafIndex = 0; leafIndex < count; leafIndex++) {
      first[superblock(frame.getLeaf(leafIndex), columns) + 1]++;
    }
    for (int superblockIndex = 0; superblockIndex < superblocks; superblockIndex++) {
      first[superblockIndex + 1] += first[superblockIndex];
    }
    final int[] next = Arrays.copyOf(first, superblocks);
    final int[] order = new int[count];
    for (int leafIndex = 0; leafIndex < count; leafIndex++) {
      order[next[superblock(frame.getLeaf(leafIndex), columns)]++] = leafIndex;
    }
    final TreeNode[] roots = new TreeNode[superblocks];
    workers.forEach(superblocks, HashMap<Long, TreeNode>::new, (leaves, superblockIndex) -> {
      leaves.clear();
      for (int orderIndex = first[superblockIndex]; orderIndex < first[superblockIndex + 1]; orderIndex++) {
        final Mcv2Frame.Leaf leaf = frame.getLeaf(order[orderIndex]);
        leaves.put(key(leaf.x(), leaf.y(), leaf.size()), nodes[order[orderIndex]]);
      }
      roots[superblockIndex] = visit(leaves, (superblockIndex % columns) * ROOT_SIZE, (superblockIndex / columns) * ROOT_SIZE, ROOT_SIZE);
    });
    return Arrays.asList(roots);
  }

  /** The superblock, in raster order, a leaf lies in. */
  private static int superblock(final Mcv2Frame.Leaf leaf, final int columns) {
    return (leaf.y() / ROOT_SIZE) * columns + leaf.x() / ROOT_SIZE;
  }

  /**
   * A leaf of a frame as a node of the wide form.
   *
   * @param selectors the frame's selector tables, by leaf size from the smallest
   */
  private static TreeNode node(
    final Mcv2Frame frame,
    final byte[] data,
    final Mcv2Frame.Leaf leaf,
    final byte @Nullable [] endpoints,
    final List<byte @Nullable []> selectors,
    final boolean hasDefaultSolid
  ) throws Mcv2Exception {
    final int mode = leaf.mode();
    if (mode == MODE_IMMEDIATE_MOTION) {
      return TreeNode.leaf(MODE_MOTION, 0, new byte[] { (byte) leaf.offset(), (byte) (leaf.offset() >> Byte.SIZE) });
    }
    if (mode == MODE_PATTERN) {
      final byte @Nullable [] table = selectors.get(Integer.numberOfTrailingZeros(leaf.size() / SMALLEST_BLOCK));
      final PatternRecord record = PatternRecord.expand(data, leaf.offset(), leaf.size(), endpoints, table);
      return TreeNode.leaf(MODE_PALETTE, 0, fullPalette(record, leaf.size()));
    }
    if (mode == MODE_SKIP && hasDefaultSolid) {
      final int color = frame.getDefaultColor();
      return TreeNode.leaf(MODE_SOLID, 0, new byte[] { (byte) (color >> 16), (byte) (color >> 8), (byte) color });
    }
    if (mode == MODE_SKIP) {
      return TreeNode.skip();
    }
    final int length = mode == MODE_COMPACT ? CompactRecord.parse(data, leaf.offset(), leaf.q()).length() : recordSize(mode, leaf.size());
    final byte[] record = new byte[length];
    System.arraycopy(data, leaf.offset(), record, 0, length);
    return TreeNode.leaf(mode, leaf.q(), record);
  }

  /** A block's key in the leaf map: its position and size, each in its own bits. */
  private static long key(final int left, final int top, final int size) {
    return ((long) left << KEY_X_SHIFT) | ((long) top << KEY_Y_SHIFT) | size;
  }

  private static TreeNode visit(final Map<Long, TreeNode> leaves, final int left, final int top, final int size) {
    final TreeNode leaf = leaves.get(key(left, top, size));
    if (leaf != null) {
      return leaf;
    }
    final int half = size / 2;
    return TreeNode.split(
      visit(leaves, left, top, half),
      visit(leaves, left + half, top, half),
      visit(leaves, left, top + half, half),
      visit(leaves, left + half, top + half, half)
    );
  }

  /** The full palette record a pattern stands for: two endpoints, then one selector bit per pixel. */
  static byte[] fullPalette(final PatternRecord record, final int size) {
    final byte[] full = new byte[recordSize(MODE_PALETTE, size)];
    putColor(full, 0, record.getColor0());
    putColor(full, CHANNELS, record.getColor1());
    for (int row = 0; row < size; row++) {
      for (int column = 0; column < size; column++) {
        final int index = row * size + column;
        final int selector = record.getSelector(record.getOrientation() == 0 ? column : row);
        full[ENDPOINT_PAIR_BYTES + index / Byte.SIZE] |= (byte) (selector << (index % Byte.SIZE));
      }
    }
    return full;
  }

  private static void putColor(final byte[] target, final int at, final int color) {
    target[at] = (byte) (color >> 16);
    target[at + 1] = (byte) (color >> 8);
    target[at + 2] = (byte) color;
  }

  /**
   * Rewrites a palette leaf whose selectors repeat exactly along one axis as a pattern leaf, like the reference's
   * {@code pattern.compact_record}; rows are tried first, then columns.
   *
   * @param record the full palette record: two endpoints, then one selector bit per pixel
   * @param size   the leaf size
   * @return the pattern record, or null when the selectors do not repeat along an axis
   */
  public static byte @Nullable [] patternRecord(final byte[] record, final int size) {
    for (int kind = 0; kind < 2; kind++) {
      boolean repeats = true;
      for (int row = 0; row < size && repeats; row++) {
        for (int column = 0; column < size; column++) {
          final int axis = kind == 0 ? bit(record, column) : bit(record, row * size);
          if (bit(record, row * size + column) != axis) {
            repeats = false;
            break;
          }
        }
      }
      if (repeats) {
        final byte[] pattern = new byte[patternSize(size, false, false)];
        System.arraycopy(record, 0, pattern, 0, ENDPOINT_PAIR_BYTES);
        pattern[ENDPOINT_PAIR_BYTES] = (byte) kind;
        final int axis = ENDPOINT_PAIR_BYTES + 1;
        for (int position = 0; position < size; position++) {
          final int value = kind == 0 ? bit(record, position) : bit(record, position * size);
          pattern[axis + position / Byte.SIZE] |= (byte) (value << (position % Byte.SIZE));
        }
        return pattern;
      }
    }
    return null;
  }

  private static int bit(final byte[] record, final int index) {
    return (record[ENDPOINT_PAIR_BYTES + index / Byte.SIZE] >> (index % Byte.SIZE)) & 1;
  }

  /**
   * Rewrites every pattern leaf as the full palette leaf it stands for, the inverse of {@link #withPatterns}.
   *
   * @param node the root
   * @param size the root's size
   * @return the rewritten tree
   * @throws Mcv2Exception if a pattern record is invalid
   */
  public static TreeNode withPalettes(final TreeNode node, final int size) throws Mcv2Exception {
    if (node.isSplit()) {
      final int half = size / 2;
      return TreeNode.split(
        withPalettes(node.getChild(0), half),
        withPalettes(node.getChild(1), half),
        withPalettes(node.getChild(2), half),
        withPalettes(node.getChild(3), half)
      );
    }
    if (node.getMode() != MODE_PATTERN) {
      return node;
    }
    final PatternRecord pattern = PatternRecord.expand(node.record(), 0, size, null, null);
    return TreeNode.leaf(MODE_PALETTE, 0, fullPalette(pattern, size));
  }

  /**
   * Rewrites every palette leaf whose selectors repeat along an axis as a pattern leaf, losslessly.
   *
   * @param node the root
   * @param size the root's size
   * @return the rewritten tree
   */
  public static TreeNode withPatterns(final TreeNode node, final int size) {
    if (node.isSplit()) {
      final int half = size / 2;
      return TreeNode.split(
        withPatterns(node.getChild(0), half),
        withPatterns(node.getChild(1), half),
        withPatterns(node.getChild(2), half),
        withPatterns(node.getChild(3), half)
      );
    }
    if (node.getMode() == MODE_PALETTE) {
      final byte[] pattern = patternRecord(node.record(), size);
      if (pattern != null) {
        return TreeNode.leaf(MODE_PATTERN, 0, pattern);
      }
    }
    return node;
  }
}
