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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.motion;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.pattern;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.predicted;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.solid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.split;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class Mcv2ParserRulesTest {

  private static final byte[] ENDPOINTS = { 0, 0, 0, -1, -1, -1 };
  private static final byte[] OTHER_ENDPOINTS = { -1, 0, 0, 0, 0, -1 };

  private record Layout(int counts, int plane, int walk, int start) {
    private static Layout of(final byte[] frame) {
      final int width = Mcv2Decoder.u16(frame, 8);
      final int height = Mcv2Decoder.u16(frame, 10);
      final int roots = ((width + 31) / 32) * ((height + 31) / 32);
      final int groups = (roots + 31) / 32;
      final int counts = 20 + 4 * (groups + (groups + 7) / 8);
      final int descriptors = (int) (Mcv2Decoder.u32(frame, counts) +
        Mcv2Decoder.u32(frame, counts + 4) +
        Mcv2Decoder.u32(frame, counts + 8));
      final int walk = counts + 12 + descriptors;
      return new Layout(counts, counts + 12, walk, walk + 4 * ((descriptors + 7) / 8));
    }
  }

  private static Node patterns8() {
    return split(split(pattern(8, ENDPOINTS, 1, 0x3C)));
  }

  private static byte[] patterns() {
    return keyframe(64, 32, patterns8(), split(split(pattern(8, OTHER_ENDPOINTS, 1, 0x3C))));
  }

  private static byte[] withByte(final byte[] frame, final int offset, final int value) {
    final byte[] copy = frame.clone();
    copy[offset] = (byte) value;
    return copy;
  }

  private static byte[] withWord(final byte[] frame, final int offset, final long value) {
    final byte[] copy = frame.clone();
    Mcv2Decoder.putU32(copy, offset, value);
    return copy;
  }

  private static String message(final byte[] frame) {
    return assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame)).getMessage();
  }

  @Test
  void theDerivedFixturesHaveTheExpectedShape() throws Mcv2Exception {
    final byte[] frame = patterns();
    final Layout layout = Layout.of(frame);
    assertEquals(6 * 4, layout.start() - layout.walk());
    assertEquals(32, Mcv2Decoder.parse(frame).getLeafCount());
  }

  @Test
  void refusesASplitWithAQuantizerInTheDerivedForm() throws Mcv2Exception {
    final byte[] frame = keyframe(32, 32, split(solid(1, 2, 3)));
    Mcv2Decoder.parse(frame);
    final int descriptor = Layout.of(frame).plane();
    assertEquals(Mcv2Decoder.MODE_SPLIT, frame[descriptor]);
    assertEquals("Invalid descriptor", message(withByte(frame, descriptor, Mcv2Decoder.MODE_SPLIT | (1 << 5))));
  }

  @Test
  void refusesEveryInvalidLeafModeInTheDerivedForm() throws Mcv2Exception {
    final byte[] frame = predicted(32, 32, motion(1, 1));
    Mcv2Decoder.parse(frame);
    final int descriptor = Layout.of(frame).plane();
    for (int mode = 7; mode < 32; mode++) {
      assertEquals("Invalid descriptor", message(withByte(frame, descriptor, mode)));
    }
  }

  @Test
  void readsEveryStoredCheckpointOfALongDirectory() throws Mcv2Exception {
    final List<Node> roots = new ArrayList<>();
    for (int index = 0; index < 33 * 9; index++) {
      roots.add(index % 7 == 0 ? motion(1, 1) : Node.skip());
    }
    final byte[] frame = Mcv2Trees.write(32 * 33, 32 * 9, 1, 0, false, roots);
    assertEquals(33 * 9, Mcv2Decoder.parse(frame).getLeafCount());
    final int groups = (33 * 9 + 31) / 32;
    assertEquals(37, Mcv2Decoder.u32(frame, 20 + groups * 4 + 4));
    assertEquals("Invalid directory checkpoint", message(withWord(frame, 20 + groups * 4 + 4, 0)));
  }

  @Test
  void refusesAnIndexThatCannotContainItsChildQuartet() throws Mcv2Exception {
    final Node quartet = Node.split(motion(1, 1), motion(2, 2), motion(3, 3), motion(4, 4));
    final byte[] frame = predicted(32, 32, quartet);
    Mcv2Decoder.parse(frame);
    final Layout layout = Layout.of(frame);
    assertEquals(Mcv2Decoder.MODE_SPLIT, frame[layout.plane()]);
    assertEquals(5, layout.walk() - layout.plane());
    assertEquals("Invalid index length", message(withWord(frame, layout.counts() + 4, 40)));
    assertEquals("Truncated index", message(Arrays.copyOf(frame, layout.counts() + 11)));
  }

  @Test
  void refusesTemporalLeavesInAKeyframe() {
    for (final Node node : List.of(motion(1, 1), Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 0, 5 }))) {
      final byte[] frame = predicted(32, 32, node);
      Mcv2Decoder.putU32(frame, 16, 1);
      assertEquals("Invalid descriptor", message(frame));
    }
  }
}
