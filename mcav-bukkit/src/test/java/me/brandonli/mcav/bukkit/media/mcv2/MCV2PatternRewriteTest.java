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
  void encodesRepeatingSelectorsAsPatternsAtEveryLeafSize() throws Mcv2Exception {
    for (final int size : new int[] { 8, 16, 32 }) {
      for (final int orientation : new int[] { 0, 1 }) {
        final byte[] palette = new byte[Mcv2Decoder.recordSize(Mcv2Decoder.MODE_PALETTE, size)];
        System.arraycopy(ENDPOINTS, 0, palette, 0, ENDPOINTS.length);
        final byte[] expected = new byte[Mcv2Decoder.patternSize(size)];
        System.arraycopy(ENDPOINTS, 0, expected, 0, ENDPOINTS.length);
        expected[6] = (byte) orientation;
        for (int axis = 0; axis < size / 8; axis++) {
          expected[7 + axis] = (byte) (0xA5 ^ (axis * 37));
        }
        for (int row = 0; row < size; row++) {
          for (int column = 0; column < size; column++) {
            final int axis = orientation == 0 ? column : row;
            final int bit = (expected[7 + axis / 8] >> (axis % 8)) & 1;
            final int pixel = row * size + column;
            palette[6 + pixel / 8] |= (byte) (bit << (pixel % 8));
          }
        }
        final byte[] output = new byte[expected.length];
        assertTrue(
          (boolean) Mcv2Internals.invoke(
            MCV2.class,
            null,
            "patternRecord",
            new Class<?>[] { byte[].class, int.class, byte[].class },
            palette,
            size,
            output
          )
        );
        assertArrayEquals(expected, output);
        final byte[] source = new byte[size * size * 3];
        for (int pixel = 0; pixel < size * size; pixel++) {
          final int color = (palette[6 + pixel / 8] >> (pixel % 8)) & 1;
          System.arraycopy(ENDPOINTS, color * 3, source, pixel * 3, 3);
        }
        for (final double lambda : new double[] { 0, 1e-300, 60, 72 }) {
          final Mcv2BlockState state = new Mcv2BlockState(source, new byte[0], size, size, true, false, lambda, null);
          state.code(size, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
          assertEquals(Mcv2Decoder.MODE_PATTERN, state.mode(0, 0));
          final byte[] record = state.record(0, 0);
          final Node leaf = Node.leaf(Mcv2Decoder.MODE_PATTERN, 0, record);
          final Node root = size == 32 ? leaf : size == 16 ? Mcv2Trees.split(leaf) : Mcv2Trees.split(Mcv2Trees.split(leaf));
          assertArrayEquals(source, Mcv2Decoder.decode(Mcv2Decoder.parse(Mcv2Trees.keyframe(size, size, root)), null, 0));
        }
      }
    }
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
