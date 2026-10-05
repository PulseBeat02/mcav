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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.DERIVED;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.DERIVED_565;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.keyframe;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.pattern;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.solid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frames.split;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import me.brandonli.mcav.bukkit.media.mcv2.FrameParser;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import org.junit.jupiter.api.Test;

/**
 * Tests that the derived form names its pattern endpoints in a table only when the table saves bytes: a table costs
 * an entry per pair and a byte, and saves a few bytes per use; and that the payload the derived form's 16-bit offsets
 * must reach counts that saving.
 */
final class FrameWriterEndpointTableTest {

  private static final byte[] EXACT_ENDPOINTS = { 0, 0, 0, (byte) 255, (byte) 255, (byte) 255 };

  private static final byte[] ENDPOINTS = { 1, 2, 3, (byte) 250, (byte) 251, (byte) 252 };

  private static final int TABLE_FLAGS = Mcv2Format.ENDPOINT_TABLE | Mcv2Format.ENDPOINT_565;

  /** A 32x32 root whose only pattern leaf uses the pair once. */
  private static TreeNode onePatternLeaf(final byte[] endpoints) {
    final TreeNode corner = TreeNode.split(pattern(8, endpoints, 0, 0x0F), solid(1, 2, 3), solid(1, 2, 3), solid(1, 2, 3));
    final TreeNode plain = TreeNode.split(solid(4, 5, 6), solid(4, 5, 6), solid(4, 5, 6), solid(4, 5, 6));
    return TreeNode.split(corner, plain, plain, plain);
  }

  private static int tableFlags(final byte[] frame) throws Mcv2Exception {
    return FrameParser.parse(frame).getFlags() & TABLE_FLAGS;
  }

  @Test
  void namesAnRgb565PairOnlyWhenItIsUsedMoreThanOnce() throws Mcv2Exception {
    // one use of a 565 pair: four bytes of entry and a byte of count against five bytes saved, which saves nothing
    assertEquals(0, tableFlags(keyframe(32, 32, DERIVED_565, onePatternLeaf(EXACT_ENDPOINTS))));
    final TreeNode root = onePatternLeaf(EXACT_ENDPOINTS);
    assertEquals(TABLE_FLAGS, tableFlags(keyframe(64, 32, DERIVED_565, root, root)));
  }

  @Test
  void namesAFullPairOnlyWhenItIsUsedMoreThanOnce() throws Mcv2Exception {
    assertEquals(0, tableFlags(keyframe(32, 32, DERIVED, onePatternLeaf(ENDPOINTS))));
    final TreeNode root = onePatternLeaf(ENDPOINTS);
    assertEquals(Mcv2Format.ENDPOINT_TABLE, tableFlags(keyframe(64, 32, DERIVED, root, root)));
  }

  @Test
  void countsTheTableInThePayloadTheDerivedOffsetsMustReach() throws Mcv2Exception {
    // 16384 pattern leaves of one pair: two bytes each with the table and the selector words, seven without the table,
    // so the payload fits the derived form's 16-bit offsets only once the table's saving is counted
    final TreeNode root = split(split(pattern(8, ENDPOINTS, 0, 0x0F)));
    final TreeNode[] roots = new TreeNode[32 * 32];
    Arrays.fill(roots, root);
    final int flags = FrameParser.parse(keyframe(1024, 1024, DERIVED, roots)).getFlags();
    assertTrue((flags & Mcv2Format.DERIVED_OFFSETS) != 0, "the derived form");
    assertEquals(Mcv2Format.ENDPOINT_TABLE, flags & TABLE_FLAGS);
  }
}
