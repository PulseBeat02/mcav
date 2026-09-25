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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Block tree nodes: validated leaves, splits of four children, value equality. */
final class TreeNodeTest {

  private static final TreeNode SOLID = TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 });

  @Test
  void skipIsASharedEmptyLeaf() {
    final TreeNode skip = TreeNode.skip();
    assertSame(skip, TreeNode.skip());
    assertEquals(Mcv2Format.MODE_SKIP, skip.getMode());
    assertEquals(0, skip.getQ());
    assertFalse(skip.isSplit());
    assertEquals(0, skip.getRecord().length);
    assertEquals("A leaf has no children", assertThrows(IllegalStateException.class, () -> skip.getChild(0)).getMessage());
  }

  @Test
  void leavesKeepACopyOfTheirRecord() {
    final byte[] record = { 1, 2, 3 };
    final TreeNode leaf = TreeNode.leaf(Mcv2Format.MODE_SOLID, 5, record);
    record[0] = 9;
    assertArrayEquals(new byte[] { 1, 2, 3 }, leaf.getRecord());
    assertNotSame(leaf.getRecord(), leaf.getRecord());
    assertEquals(5, leaf.getQ());
    assertEquals("leaf(2,5,3B)", leaf.toString());
  }

  @ParameterizedTest
  @ValueSource(ints = { -1, Mcv2Format.MODE_SPLIT, 32 })
  void refusesModesThatAreNotLeaves(final int mode) {
    assertThrows(IllegalArgumentException.class, () -> TreeNode.leaf(mode, 0, new byte[0]));
  }

  @ParameterizedTest
  @ValueSource(ints = { -1, 8 })
  void refusesQuantizersOutsideThreeBits(final int q) {
    assertThrows(IllegalArgumentException.class, () -> TreeNode.leaf(Mcv2Format.MODE_SOLID, q, new byte[3]));
  }

  @Test
  void splitsHoldFourChildrenInRasterOrder() {
    final TreeNode split = TreeNode.split(SOLID, TreeNode.skip(), TreeNode.skip(), SOLID);
    assertTrue(split.isSplit());
    assertEquals(Mcv2Format.MODE_SPLIT, split.getMode());
    assertSame(SOLID, split.getChild(0));
    assertSame(SOLID, split.getChild(3));
    assertEquals("split[leaf(2,0,3B), leaf(0,0,0B), leaf(0,0,0B), leaf(2,0,3B)]", split.toString());
    assertThrows(NullPointerException.class, () -> TreeNode.split(SOLID, SOLID, SOLID, null));
  }

  @Test
  void comparesModeQuantizerRecordAndChildren() {
    EqualityAssertions.assertEqualityContract(
      SOLID,
      TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 }),
      TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { 1, 2, 3 }),
      TreeNode.leaf(Mcv2Format.MODE_SOLID, 1, new byte[] { 1, 2, 3 }),
      TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 4 })
    );
    final TreeNode split = TreeNode.split(SOLID, SOLID, SOLID, SOLID);
    EqualityAssertions.assertEqualityContract(
      split,
      TreeNode.split(SOLID, SOLID, SOLID, TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 })),
      TreeNode.split(SOLID, SOLID, SOLID, TreeNode.skip()),
      TreeNode.leaf(Mcv2Format.MODE_SPLIT - 1, 0, new byte[0])
    );
  }
}
