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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.media.mcv2.CompactRecord;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.PatternRecord;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Rebuilds the block tree of a validated frame, like the reference's {@code repack.roots_from_frame}: every leaf keeps
 * its mode, quantizer and record in the wide form, immediate motion becomes an ordinary motion leaf, a pattern becomes
 * the full palette it stands for, and a skipped keyframe leaf becomes the default colour's solid leaf. Serializing the
 * result with the frame's own options reproduces the frame byte for byte.
 */
public final class TreeReader {

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
    final byte[] data = frame.getData();
    final Map<Long, TreeNode> leaves = new HashMap<>();
    final boolean defaultSolid = (frame.getFlags() & DEFAULT_SOLID) != 0;
    final byte@Nullable[] endpoints = endpointTable(frame);
    for (int i = 0; i < frame.getLeafCount(); i++) {
      final Mcv2Frame.Leaf leaf = frame.getLeaf(i);
      final int mode = leaf.mode();
      final TreeNode node;
      if (mode == MODE_IMMEDIATE_MOTION) {
        node = TreeNode.leaf(MODE_MOTION, 0, new byte[] { (byte) leaf.offset(), (byte) (leaf.offset() >> 8) });
      } else if (mode == MODE_PATTERN) {
        final PatternRecord record = PatternRecord.expand(data, leaf.offset(), leaf.size(), endpoints, selectorTable(frame, leaf.size()));
        node = TreeNode.leaf(MODE_PALETTE, 0, fullPalette(record, leaf.size()));
      } else if (mode == MODE_SKIP && defaultSolid) {
        final int color = frame.getDefaultColor();
        node = TreeNode.leaf(MODE_SOLID, 0, new byte[] { (byte) (color >> 16), (byte) (color >> 8), (byte) color });
      } else if (mode == MODE_SKIP) {
        node = TreeNode.skip();
      } else {
        final int length = mode == MODE_COMPACT
          ? CompactRecord.parse(data, leaf.offset(), leaf.q()).length()
          : recordSize(mode, leaf.size());
        final byte[] record = new byte[length];
        System.arraycopy(data, leaf.offset(), record, 0, length);
        node = TreeNode.leaf(mode, leaf.q(), record);
      }
      leaves.put(key(leaf.x(), leaf.y(), leaf.size()), node);
    }
    final List<TreeNode> roots = new ArrayList<>();
    for (int y = 0; y < frame.getHeight(); y += ROOT_SIZE) {
      for (int x = 0; x < frame.getWidth(); x += ROOT_SIZE) {
        roots.add(visit(leaves, x, y, ROOT_SIZE));
      }
    }
    return roots;
  }

  private static long key(final int x, final int y, final int size) {
    return ((long) x << 40) | ((long) y << 16) | size;
  }

  private static TreeNode visit(final Map<Long, TreeNode> leaves, final int x, final int y, final int size) {
    final TreeNode leaf = leaves.get(key(x, y, size));
    if (leaf != null) {
      return leaf;
    }
    final int half = size / 2;
    return TreeNode.split(
      visit(leaves, x, y, half),
      visit(leaves, x + half, y, half),
      visit(leaves, x, y + half, half),
      visit(leaves, x + half, y + half, half)
    );
  }

  private static byte@Nullable[] endpointTable(final Mcv2Frame frame) {
    return frame.getEndpointTable();
  }

  private static byte@Nullable[] selectorTable(final Mcv2Frame frame, final int size) {
    return frame.getSelectorTable(size);
  }

  /** The full palette record a pattern stands for: two endpoints, then one selector bit per pixel. */
  static byte[] fullPalette(final PatternRecord record, final int size) {
    final byte[] full = new byte[6 + (size * size) / 8];
    putColor(full, 0, record.getColor0());
    putColor(full, 3, record.getColor1());
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        final int index = y * size + x;
        final int selector = record.getSelector(record.getOrientation() == 0 ? x : y);
        full[6 + index / 8] |= (byte) (selector << (index & 7));
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
  public static byte@Nullable[] patternRecord(final byte[] record, final int size) {
    for (int kind = 0; kind < 2; kind++) {
      boolean repeats = true;
      for (int y = 0; y < size && repeats; y++) {
        for (int x = 0; x < size; x++) {
          final int axis = kind == 0 ? bit(record, x) : bit(record, y * size);
          if (bit(record, y * size + x) != axis) {
            repeats = false;
            break;
          }
        }
      }
      if (repeats) {
        final byte[] pattern = new byte[7 + size / 8];
        System.arraycopy(record, 0, pattern, 0, 6);
        pattern[6] = (byte) kind;
        for (int i = 0; i < size; i++) {
          final int value = kind == 0 ? bit(record, i) : bit(record, i * size);
          pattern[7 + i / 8] |= (byte) (value << (i & 7));
        }
        return pattern;
      }
    }
    return null;
  }

  private static int bit(final byte[] record, final int index) {
    return (record[6 + index / 8] >> (index & 7)) & 1;
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
