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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.solid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.split;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The frame parser: valid version 3 frames expose their header and leaves, and every header rule is enforced.
 */
final class Mcv2ParserTest {

  private static byte[] palette() {
    final byte[] record = new byte[38];
    new Random(7).nextBytes(record);
    return record;
  }

  @Test
  void absentRootsHaveNoRecordOffset() throws Mcv2Exception {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(Mcv2Trees.predicted(1, 1, Node.skip()));
    assertEquals(1, frame.getLeafCount());
    assertEquals(-1, frame.getLeaf(0).offset());
  }

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Decoder.class);
  }

  @Test
  void parsesTheHeaderAndTheLeavesOfAKeyframe() throws Mcv2Exception {
    final byte[] frame = keyframe(40, 20, solid(1, 2, 3), split(Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, palette())));
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
    assertEquals(40, parsed.getWidth());
    assertEquals(20, parsed.getHeight());
    assertEquals(0, parsed.getFrameId());
    assertEquals(0, parsed.getReferenceId());
    assertTrue(parsed.isKeyframe());
    assertEquals(frame.length, parsed.getData().length);
    assertArrayEquals(frame, parsed.getData());
    assertNotSame(parsed.getData(), parsed.getData());
    assertEquals(5, parsed.getLeafCount());
    final List<Mcv2Decoder.Leaf> leaves = new ArrayList<>();
    for (int leafIndex = 0; leafIndex < parsed.getLeafCount(); leafIndex++) {
      leaves.add(parsed.getLeaf(leafIndex));
    }
    // The solid root precedes the second root's four palette children in level order.
    assertEquals(new Mcv2Decoder.Leaf(32, 0, 16, Mcv2Decoder.MODE_PALETTE, 0, parsed.getPayloadStart() + 3), leaves.get(1));
    assertEquals(new Mcv2Decoder.Leaf(0, 0, 32, Mcv2Decoder.MODE_SOLID, 0, parsed.getPayloadStart()), leaves.get(0));
    assertThrows(IndexOutOfBoundsException.class, () -> parsed.getLeaf(5));
  }

  @Test
  void copiesTheInputSoTheCallerMayReuseIt() throws Mcv2Exception {
    final byte[] frame = keyframe(8, 8, solid(9, 9, 9));
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
    frame[12] = 1;
    assertEquals(0, parsed.getData()[12]);
  }

  @Test
  void rejectsBytesThatAreNotAnMcv2Frame() {
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[] { 'M', 'C', 'V' }));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[48]));
    final Mcv2Exception mcv1 = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[] { 'M', 'C', 'V', '1' }));
    assertEquals("MCV1 version 1 is no longer supported; re-encode as MCV2 version 3", mcv1.getMessage());
    assertThrows(NullPointerException.class, () -> Mcv2Decoder.parse(null));
  }

  @Test
  void rejectsFramesOfTheWrongLength() {
    final byte[] frame = keyframe(8, 8, solid(1, 1, 1));
    assertEquals("Invalid frame length", message(Arrays.copyOf(frame, 19)));
    final byte[] huge = new byte[Mcv2Decoder.MAX_FRAME_BYTES + 1];
    System.arraycopy(frame, 0, huge, 0, 4);
    assertEquals("Invalid frame length", message(huge));
  }

  @Test
  void judgesTheLengthOfBytesThatStartLikeAFrame() {
    final byte[] magic = new byte[Integer.BYTES];
    Mcv2Decoder.putU32(magic, 0, Mcv2Decoder.MAGIC);
    assertEquals("Invalid frame length", message(magic));
    // the shortest and the longest frames pass the length check and reach the header's
    assertEquals("Not an MCV2 version 3 frame", message(Arrays.copyOf(magic, Mcv2Decoder.HEADER_BYTES)));
    assertEquals("Not an MCV2 version 3 frame", message(Arrays.copyOf(magic, Mcv2Decoder.MAX_FRAME_BYTES)));
  }

  private static Stream<Arguments> headerRules() {
    final byte[] key = keyframe(40, 40, solid(1, 2, 3), solid(4, 5, 6), solid(1, 2, 3), solid(7, 8, 9));
    return Stream.of(
      Arguments.of(withWord(key, 4, 0x0104), "Not an MCV2 version 3 frame"),
      Arguments.of(withWord(key, 4, 0x0303), "Not an MCV2 version 3 frame"),
      Arguments.of(withWord(key, 4, 0x010103), "Not an MCV2 version 3 frame"),
      Arguments.of(withWord(key, 4, 0x01000003), "Not an MCV2 version 3 frame"),
      Arguments.of(withWord(key, 8, 40L << 16), "Invalid dimensions"),
      Arguments.of(withWord(key, 8, 4097 | (40L << 16)), "Invalid dimensions"),
      Arguments.of(withWord(key, 8, 40), "Invalid dimensions"),
      Arguments.of(withWord(key, 8, 40 | (4097L << 16)), "Invalid dimensions")
    );
  }

  private static byte[] withWord(final byte[] frame, final int offset, final long value) {
    final byte[] changed = frame.clone();
    Mcv2Decoder.putU32(changed, offset, value);
    return changed;
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("headerRules")
  void enforcesEveryHeaderRule(final byte[] frame, final String expected) {
    assertEquals(expected, message(frame));
  }

  private static String message(final byte[] frame) {
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame));
    return exception.getMessage();
  }
}
