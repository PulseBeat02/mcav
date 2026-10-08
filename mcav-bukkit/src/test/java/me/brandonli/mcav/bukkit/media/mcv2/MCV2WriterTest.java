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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.keyframe;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.predicted;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.solid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.split;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class MCV2WriterTest {

  @Test
  void refusesInvalidDimensionsAndRootCounts() {
    final List<Node> one = List.of(solid(1, 1, 1));
    for (final int[] size : new int[][] { { 0, 32 }, { 4097, 32 }, { 32, 0 }, { 32, 4097 } }) {
      assertThrows(IllegalArgumentException.class, () -> Mcv2Trees.write(size[0], size[1], 0, 0, true, one));
    }
    assertThrows(IllegalArgumentException.class, () -> Mcv2Trees.write(64, 32, 0, 0, true, one));
    assertThrows(NullPointerException.class, () ->
      Mcv2Internals.call(
        MCV2.class,
        null,
        "write",
        new Class<?>[] { int.class, int.class, boolean.class, List.class, long.class, long.class },
        32,
        32,
        true,
        null,
        0L,
        0L
      )
    );
  }

  @Test
  void refusesLeavesThatAreNotLeaves() {
    for (int mode = 7; mode <= 31; mode++) {
      final Node node = Node.leaf(mode, 0, new byte[2]);
      assertEquals("Illegal leaf mode " + mode, assertThrows(IllegalArgumentException.class, () -> predicted(32, 32, node)).getMessage());
    }
    final Node shortRecord = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[2]);
    assertEquals(
      "Record length disagrees with its mode",
      assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, shortRecord)).getMessage()
    );
    final Node deep = split(split(split(solid(1, 1, 1))));
    assertEquals("Split below the bounded depth", assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, deep)).getMessage());
  }

  @Test
  void refusesATreeThatDoesNotSerializeToAValidFrame() {
    for (final Node temporal : List.of(Mcv2Trees.motion(1, 1), Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 0, 1 }))) {
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, temporal));
      assertTrue(exception.getMessage().startsWith("The tree does not serialize to a valid frame"));
    }
    for (final byte[] record : new byte[][] { {}, { 3 }, { 0x30 }, { 0 }, { 0, 1, 2 } }) {
      assertThrows(IllegalArgumentException.class, () -> predicted(32, 32, Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, record)));
    }
    final byte[] pattern = new byte[11];
    pattern[6] = 2;
    assertThrows(IllegalArgumentException.class, () -> keyframe(32, 32, Node.leaf(Mcv2Decoder.MODE_PATTERN, 0, pattern)));
  }

  @Test
  void acceptsAQuantizerOnlyWhereTheModeHasOne() throws Mcv2Exception {
    for (final Node node : List.of(
      Node.leaf(Mcv2Decoder.MODE_MOTION, 1, new byte[] { 1, 1 }),
      Node.leaf(Mcv2Decoder.MODE_SOLID, 7, new byte[3])
    )) {
      assertEquals(
        "Quantizer on a mode without one",
        assertThrows(IllegalArgumentException.class, () -> predicted(32, 32, node)).getMessage()
      );
    }
    final Node coarse = Node.leaf(Mcv2Decoder.MODE_COMPACT, 7, new byte[] { 0, 1 });
    final Node compact = Node.leaf(Mcv2Decoder.MODE_COMPACT, 4, new byte[] { 0, 5 });
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(predicted(64, 32, coarse, compact));
    assertEquals(7, frame.getLeaf(0).quantizer());
    assertEquals(4, frame.getLeaf(1).quantizer());
  }

  @Test
  void writesNoDescriptorsForAbsentRoots() throws Mcv2Exception {
    final byte[] skipped = predicted(64, 32, Node.skip(), Node.skip());
    assertEquals(40, skipped.length);
    assertEquals(2, Mcv2Decoder.parse(skipped).getLeafCount());
    final byte[] uniform = keyframe(64, 32, solid(3, 3, 3), solid(3, 3, 3));
    assertEquals(52, uniform.length);
    assertEquals(3, Mcv2Decoder.u32(uniform, 20));
    assertEquals(List.of(solid(3, 3, 3), solid(3, 3, 3)), Mcv2Trees.read(Mcv2Decoder.parse(uniform)));
  }
}
