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

final class Mcv2WriterTest {

  private static final int ROOT_SIZE = 32;
  private static final int TWO_ROOT_WIDTH = 64;

  @Test
  void shouldRefuseInvalidDimensionsAndRootCounts() {
    final List<Node> one = List.of(solid(1, 1, 1));
    for (final int[] size : new int[][] { { 0, ROOT_SIZE }, { 4097, ROOT_SIZE }, { ROOT_SIZE, 0 }, { ROOT_SIZE, 4097 } }) {
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
        Mcv2Trees.write(size[0], size[1], 0, 0, Mcv2FrameKind.KEYFRAME, one)
      );
      final String message = exception.getMessage();
      assertEquals("Invalid dimensions!", message);
    }

    assertThrows(IllegalArgumentException.class, () -> Mcv2Trees.write(TWO_ROOT_WIDTH, ROOT_SIZE, 0, 0, Mcv2FrameKind.KEYFRAME, one));
    assertThrows(NullPointerException.class, () ->
      Mcv2Internals.call(
        Mcv2.class,
        null,
        "write",
        new Class<?>[] { int.class, int.class, Mcv2FrameKind.class, List.class, long.class, long.class },
        ROOT_SIZE,
        ROOT_SIZE,
        Mcv2FrameKind.KEYFRAME,
        null,
        0L,
        0L
      )
    );
  }

  @Test
  void shouldRefuseLeavesThatAreNotLeaves() {
    for (int mode = 7; mode <= 31; mode++) {
      final Node node = Node.leaf(mode, 0, new byte[2]);
      final String expected = "Leaf mode %s is illegal!".formatted(mode);
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> predicted(ROOT_SIZE, ROOT_SIZE, node));
      final String message = exception.getMessage();
      assertEquals(expected, message);
    }

    final Node shortRecord = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[2]);
    final IllegalArgumentException shortRecordException = assertThrows(IllegalArgumentException.class, () ->
      keyframe(ROOT_SIZE, ROOT_SIZE, shortRecord)
    );
    final String shortRecordMessage = shortRecordException.getMessage();
    assertEquals("Record length disagrees with its mode!", shortRecordMessage);

    final Node deep = split(split(split(solid(1, 1, 1))));
    final IllegalArgumentException deepException = assertThrows(IllegalArgumentException.class, () -> keyframe(ROOT_SIZE, ROOT_SIZE, deep));
    final String deepMessage = deepException.getMessage();
    assertEquals("Split below the bounded depth!", deepMessage);
  }

  @Test
  void shouldRefuseATreeThatDoesNotSerializeToAValidFrame() {
    for (final Node temporal : List.of(Mcv2Trees.motion(1, 1), Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[10]))) {
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
        keyframe(ROOT_SIZE, ROOT_SIZE, temporal)
      );
      final String message = exception.getMessage();
      final boolean reportsAnInvalidFrame = message.startsWith("The tree does not serialize to a valid frame");
      assertTrue(reportsAnInvalidFrame);
    }

    for (final byte[] record : new byte[][] { {}, { 3 }, { 0x30 }, { 0 }, { 0, 1, 2 } }) {
      assertThrows(IllegalArgumentException.class, () -> predicted(ROOT_SIZE, ROOT_SIZE, Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, record)));
    }

    final byte[] pattern = new byte[11];
    pattern[6] = 2;
    assertThrows(IllegalArgumentException.class, () -> keyframe(ROOT_SIZE, ROOT_SIZE, Node.leaf(Mcv2Decoder.MODE_PATTERN, 0, pattern)));
  }

  @Test
  void shouldAcceptAQuantizerOnlyWhereTheModeHasOne() {
    for (final Node node : List.of(
      Node.leaf(Mcv2Decoder.MODE_MOTION, 1, new byte[] { 1, 1 }),
      Node.leaf(Mcv2Decoder.MODE_SOLID, 2, new byte[3])
    )) {
      final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> predicted(ROOT_SIZE, ROOT_SIZE, node));
      final String message = exception.getMessage();
      assertEquals("Quantizer on a mode without one!", message);
    }

    final Node coarse = Node.leaf(Mcv2Decoder.MODE_COMPACT, 2, new byte[10]);
    final Node compact = Node.leaf(Mcv2Decoder.MODE_COMPACT, 1, new byte[10]);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(predicted(TWO_ROOT_WIDTH, ROOT_SIZE, coarse, compact));
    final int firstQuantizer = frame.getLeaf(0).quantizer();
    assertEquals(2, firstQuantizer);

    final int secondQuantizer = frame.getLeaf(1).quantizer();
    assertEquals(1, secondQuantizer);
  }

  @Test
  void shouldWriteNoDescriptorsForAbsentRoots() {
    final byte[] skipped = predicted(TWO_ROOT_WIDTH, ROOT_SIZE, Node.skip(), Node.skip());
    final int skippedLength = skipped.length;
    assertEquals(40, skippedLength);

    final int leafCount = Mcv2Decoder.parse(skipped).getLeafCount();
    assertEquals(2, leafCount);

    final byte[] uniform = keyframe(TWO_ROOT_WIDTH, ROOT_SIZE, solid(3, 3, 3), solid(3, 3, 3));
    final int uniformLength = uniform.length;
    assertEquals(52, uniformLength);

    final long firstGroupMask = Mcv2Decoder.readUnsignedInt(uniform, 20);
    assertEquals(3, firstGroupMask);

    final List<Node> expectedRoots = List.of(solid(3, 3, 3), solid(3, 3, 3));
    final List<Node> roots = Mcv2Trees.read(Mcv2Decoder.parse(uniform));
    assertEquals(expectedRoots, roots);
  }
}
