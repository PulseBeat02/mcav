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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntBinaryOperator;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class MCV2PatternRewriteTest {

  private static final byte[] ENDPOINTS = { 1, 2, 3, (byte) 250, (byte) 251, (byte) 252 };

  private static byte[] palette(final IntBinaryOperator bit) {
    final byte[] record = new byte[14];
    System.arraycopy(ENDPOINTS, 0, record, 0, 6);
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        record[6 + row] |= (byte) (bit.applyAsInt(column, row) << column);
      }
    }
    return record;
  }

  private static boolean pattern(final byte[] palette, final byte[] out) {
    return (boolean) Mcv2Internals.invoke(
      MCV2.class,
      null,
      "patternRecord",
      new Class<?>[] { byte[].class, int.class, byte[].class },
      palette,
      8,
      out
    );
  }

  private static Node rewrite(final Node node, final int size) {
    return new Node(
      Mcv2Internals.invoke(
        MCV2.class,
        null,
        "withPatterns",
        new Class<?>[] { Mcv2Internals.nested("TreeNode"), int.class },
        node.value(),
        size
      )
    );
  }

  @Test
  void findsTheRepeatingAxisRowsFirst() {
    final byte[] record = new byte[8];
    assertTrue(pattern(palette((column, row) -> column < 4 ? 1 : 0), record));
    assertArrayEquals(Mcv2Trees.pattern(8, ENDPOINTS, 0, 0x0f).getRecord(), record);
    assertTrue(pattern(palette((column, row) -> row < 4 ? 1 : 0), record));
    assertArrayEquals(Mcv2Trees.pattern(8, ENDPOINTS, 1, 0x0f).getRecord(), record);
    assertTrue(pattern(palette((column, row) -> 0), record));
    assertArrayEquals(Mcv2Trees.pattern(8, ENDPOINTS, 0, 0).getRecord(), record);
    assertFalse(pattern(palette((column, row) -> (column + row) & 1), record));
    assertFalse(pattern(palette((column, row) -> row == 7 && column == 7 ? 1 : 0), record));
  }

  @Test
  void rewritesOnlyRepeatingPalettes() {
    final Node checker = Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, palette((column, row) -> (column + row) & 1));
    assertSame(checker.value(), rewrite(checker, 8).value());
    final Node solid = Mcv2Trees.solid(1, 1, 1);
    assertSame(solid.value(), rewrite(solid, 8).value());
    final Node stripes = Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, palette((column, row) -> row & 1));
    final Node expected = Mcv2Trees.pattern(8, ENDPOINTS, 1, 0xaa);
    assertEquals(expected, rewrite(stripes, 8));
    assertEquals(Node.split(expected, solid, checker, expected), rewrite(Node.split(stripes, solid, checker, stripes), 16));
  }

  @Test
  void rebuildsKeyframeSolidsAndPatterns() throws Mcv2Exception {
    final Node patterns = Mcv2Trees.split(Mcv2Trees.pattern(16, ENDPOINTS, 1, 0x5a));
    final byte[] data = Mcv2Trees.keyframe(64, 32, Mcv2Trees.solid(4, 5, 6), patterns);
    assertEquals(List.of(Mcv2Trees.solid(4, 5, 6), patterns), Mcv2Trees.read(Mcv2Decoder.parse(data)));
  }

  @Test
  void rebuildsMotionSkipsAndCompactRecords() throws Mcv2Exception {
    final Node compact = Node.leaf(Mcv2Decoder.MODE_COMPACT, 2, new byte[] { 0, 0, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55 });
    final Node split = Node.split(compact, Node.skip(), Mcv2Trees.motion(1, 1), compact);
    final byte[] data = Mcv2Trees.predicted(96, 32, Mcv2Trees.motion(-2, 3), Node.skip(), split);
    assertEquals(List.of(Mcv2Trees.motion(-2, 3), Node.skip(), split), Mcv2Trees.read(Mcv2Decoder.parse(data)));
  }
}
