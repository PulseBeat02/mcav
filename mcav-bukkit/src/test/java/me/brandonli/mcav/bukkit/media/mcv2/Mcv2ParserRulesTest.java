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

  private static final int PATTERN_SELECTOR = 0x3C;

  private static final int HEADER_BYTES = 20;

  private static final byte[] ENDPOINTS = { 0, 0, 0, -1, -1, -1 };
  private static final byte[] OTHER_ENDPOINTS = { -1, 0, 0, 0, 0, -1 };

  private static final String INVALID_DESCRIPTOR_MESSAGE = "Invalid descriptor!";

  private Node patterns8() {
    return split(split(pattern(8, ENDPOINTS, 1, PATTERN_SELECTOR)));
  }

  private byte[] patterns() {
    return keyframe(64, 32, this.patterns8(), split(split(pattern(8, OTHER_ENDPOINTS, 1, PATTERN_SELECTOR))));
  }

  private byte[] withByte(final byte[] frame, final int offset, final int value) {
    final byte[] copy = frame.clone();
    copy[offset] = (byte) value;
    return copy;
  }

  private byte[] withWord(final byte[] frame, final int offset, final long value) {
    final byte[] copy = frame.clone();
    Mcv2Decoder.putUnsignedInt(copy, offset, value);
    return copy;
  }

  private String message(final byte[] frame) {
    return assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame)).getMessage();
  }

  @Test
  void shouldHaveASixWordWalkAndThirtyTwoLeavesInThePatternFixture() {
    final byte[] frame = this.patterns();
    final Layout layout = Layout.of(frame);
    final int expectedWalkLength = 6 * 4;
    final int walkLength = layout.start() - layout.walk();
    assertEquals(expectedWalkLength, walkLength);

    final int leafCount = Mcv2Decoder.parse(frame).getLeafCount();
    assertEquals(32, leafCount);
  }

  @Test
  void shouldRefuseASplitWithAQuantizerInTheDerivedForm() {
    final byte[] frame = keyframe(32, 32, split(solid(1, 2, 3)));
    Mcv2Decoder.parse(frame);
    final int descriptor = Layout.of(frame).plane();
    final byte descriptorMode = frame[descriptor];
    assertEquals(Mcv2Decoder.MODE_SPLIT, descriptorMode);

    final String failureMessage = this.message(this.withByte(frame, descriptor, Mcv2Decoder.MODE_SPLIT | (1 << 5)));
    assertEquals(INVALID_DESCRIPTOR_MESSAGE, failureMessage);
  }

  @Test
  void shouldRefuseASplitAtTheFirstDescriptorOfTheLastLevel() {
    final byte[] frame = keyframe(32, 32, Node.split(split(Node.skip()), Node.skip(), Node.skip(), Node.skip()));
    final Layout layout = Layout.of(frame);
    frame[layout.plane() + 1] = Mcv2Decoder.MODE_SKIP;
    frame[layout.plane() + 5] = Mcv2Decoder.MODE_SPLIT;
    final String failureMessage = this.message(frame);
    assertEquals("Split below the bounded depth!", failureMessage);
  }

  @Test
  void shouldDiagnoseMissingPresenceBitsAfterAWholeDirectorySpan() {
    final List<Node> roots = new ArrayList<>();
    roots.add(solid(1, 2, 3));
    for (int root = 1; root < 256; root++) {
      roots.add(Node.skip());
    }

    final byte[] frame = Mcv2Trees.write(4096, 64, 0, 0, Mcv2FrameKind.KEYFRAME, roots);
    final String failureMessage = this.message(this.withWord(frame, HEADER_BYTES, 0));
    assertEquals("Presence count differs from level zero!", failureMessage);
  }

  @Test
  void shouldRefuseEveryInvalidLeafModeInTheDerivedForm() {
    final byte[] frame = predicted(32, 32, motion(1, 1));
    Mcv2Decoder.parse(frame);
    final int descriptor = Layout.of(frame).plane();
    for (int mode = 7; mode < 32; mode++) {
      final String failureMessage = this.message(this.withByte(frame, descriptor, mode));
      assertEquals(INVALID_DESCRIPTOR_MESSAGE, failureMessage);
    }
  }

  @Test
  void shouldReadEveryStoredCheckpointOfALongDirectory() {
    final List<Node> roots = new ArrayList<>();
    for (int index = 0; index < 33 * 9; index++) {
      roots.add(index % 7 == 0 ? motion(1, 1) : Node.skip());
    }

    final byte[] frame = Mcv2Trees.write(32 * 33, 32 * 9, 1, 0, Mcv2FrameKind.PREDICTED, roots);
    final int expectedLeafCount = 33 * 9;
    final int leafCount = Mcv2Decoder.parse(frame).getLeafCount();
    assertEquals(expectedLeafCount, leafCount);

    final int groups = (33 * 9 + 31) / 32;
    final long checkpoint = Mcv2Decoder.readUnsignedInt(frame, HEADER_BYTES + groups * 4 + 4);
    assertEquals(37, checkpoint);

    final String failureMessage = this.message(this.withWord(frame, HEADER_BYTES + groups * 4 + 4, 0));
    assertEquals("Invalid directory checkpoint!", failureMessage);
  }

  @Test
  void shouldRefuseAnIndexThatCannotContainItsChildQuartet() {
    final Node quartet = Node.split(motion(1, 1), motion(2, 2), motion(3, 3), motion(4, 4));
    final byte[] frame = predicted(32, 32, quartet);
    Mcv2Decoder.parse(frame);
    final Layout layout = Layout.of(frame);
    final byte rootMode = frame[layout.plane()];
    assertEquals(Mcv2Decoder.MODE_SPLIT, rootMode);

    final int planeLength = layout.walk() - layout.plane();
    assertEquals(5, planeLength);

    final String indexLengthMessage = this.message(this.withWord(frame, layout.counts() + 4, 40));
    assertEquals("Invalid index length!", indexLengthMessage);

    final String truncatedMessage = this.message(Arrays.copyOf(frame, layout.counts() + 11));
    assertEquals("Truncated index!", truncatedMessage);
  }

  @Test
  void shouldRefuseTemporalLeavesInAKeyframe() {
    for (final Node node : List.of(motion(1, 1), Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[10]))) {
      final byte[] frame = predicted(32, 32, node);
      Mcv2Decoder.putUnsignedInt(frame, 16, 1);
      final String failureMessage = this.message(frame);
      assertEquals(INVALID_DESCRIPTOR_MESSAGE, failureMessage);
    }
  }

  private record Layout(int counts, int plane, int walk, int start) {
    private static Layout of(final byte[] frame) {
      final int width = Mcv2Decoder.readUnsignedShort(frame, 8);
      final int height = Mcv2Decoder.readUnsignedShort(frame, 10);
      final int roots = ((width + 31) / 32) * ((height + 31) / 32);
      final int groups = (roots + 31) / 32;
      final int counts = HEADER_BYTES + 4 * (groups + (groups + 7) / 8);
      final int descriptors = (int) (Mcv2Decoder.readUnsignedInt(frame, counts) +
        Mcv2Decoder.readUnsignedInt(frame, counts + 4) +
        Mcv2Decoder.readUnsignedInt(frame, counts + 8));
      final int walk = counts + 12 + descriptors;
      return new Layout(counts, counts + 12, walk, walk + 4 * ((descriptors + 7) / 8));
    }
  }
}
