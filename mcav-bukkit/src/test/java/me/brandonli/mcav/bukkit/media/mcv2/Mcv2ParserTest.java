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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class Mcv2ParserTest {

  private static final int FRAME_SIZE = 40;

  private static final String INVALID_LENGTH_MESSAGE = "Invalid frame length!";
  private static final String NOT_A_FRAME_MESSAGE = "Not an MCV2 version 3 frame!";
  private static final String INVALID_DIMENSIONS_MESSAGE = "Invalid dimensions!";

  private static byte[] palette() {
    final byte[] record = new byte[38];
    new Random(7).nextBytes(record);
    return record;
  }

  @Test
  void shouldGiveAbsentRootsNoRecordOffset() {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(Mcv2Trees.predicted(1, 1, Node.skip()));
    final int leafCount = frame.getLeafCount();
    assertEquals(1, leafCount);

    final int offset = frame.getLeaf(0).offset();
    assertEquals(-1, offset);
  }

  @Test
  void shouldParseTheHeaderAndTheLeavesOfAKeyframe() {
    final byte[] frame = keyframe(FRAME_SIZE, 20, solid(1, 2, 3), split(Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, palette())));
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
    final int width = parsed.getWidth();
    assertEquals(FRAME_SIZE, width);

    final int height = parsed.getHeight();
    assertEquals(20, height);

    final long frameId = parsed.getFrameId();
    assertEquals(0, frameId);

    final long referenceId = parsed.getReferenceId();
    assertEquals(0, referenceId);

    final boolean keyframe = parsed.isKeyframe();
    assertTrue(keyframe);

    final int expectedLength = frame.length;
    final int dataLength = parsed.getData().length;
    assertEquals(expectedLength, dataLength);

    final byte[] data = parsed.getData();
    assertArrayEquals(frame, data);

    final byte[] firstCopy = parsed.getData();
    final byte[] secondCopy = parsed.getData();
    assertNotSame(firstCopy, secondCopy);

    final int leafCount = parsed.getLeafCount();
    assertEquals(5, leafCount);

    final List<Mcv2Decoder.Leaf> leaves = new ArrayList<>();
    for (int leafIndex = 0; leafIndex < parsed.getLeafCount(); leafIndex++) {
      leaves.add(parsed.getLeaf(leafIndex));
    }

    // The solid root precedes the second root's four palette children in level order.
    final Mcv2Decoder.Leaf expectedPaletteLeaf = new Mcv2Decoder.Leaf(32, 0, 16, Mcv2Decoder.MODE_PALETTE, 0, parsed.getPayloadStart() + 3);
    final Mcv2Decoder.Leaf paletteLeaf = leaves.get(1);
    assertEquals(expectedPaletteLeaf, paletteLeaf);

    final Mcv2Decoder.Leaf expectedSolidLeaf = new Mcv2Decoder.Leaf(0, 0, 32, Mcv2Decoder.MODE_SOLID, 0, parsed.getPayloadStart());
    final Mcv2Decoder.Leaf solidLeaf = leaves.get(0);
    assertEquals(expectedSolidLeaf, solidLeaf);
    assertThrows(IndexOutOfBoundsException.class, () -> parsed.getLeaf(5));
  }

  @Test
  void shouldCopyTheInputSoTheCallerMayReuseIt() {
    final byte[] frame = keyframe(8, 8, solid(9, 9, 9));
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
    frame[12] = 1;

    final byte copiedByte = parsed.getData()[12];
    assertEquals(0, copiedByte);
  }

  @Test
  void shouldRejectBytesThatAreNotAnMcv2Frame() {
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[] { 'M', 'C', 'V' }));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[48]));

    final Mcv2Exception mcv1 = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(new byte[] { 'M', 'C', 'V', '1' }));
    final String failureMessage = mcv1.getMessage();
    assertEquals("MCV1 version 1 is no longer supported; re-encode as MCV2 version 3!", failureMessage);

    assertThrows(NullPointerException.class, () -> Mcv2Decoder.parse(null));
  }

  @Test
  void shouldRejectFramesOfTheWrongLength() {
    final byte[] frame = keyframe(8, 8, solid(1, 1, 1));
    final String truncatedMessage = this.message(Arrays.copyOf(frame, 19));
    assertEquals(INVALID_LENGTH_MESSAGE, truncatedMessage);

    final byte[] huge = new byte[Mcv2Decoder.MAXIMUM_FRAME_BYTES + 1];
    System.arraycopy(frame, 0, huge, 0, 4);
    final String hugeMessage = this.message(huge);
    assertEquals(INVALID_LENGTH_MESSAGE, hugeMessage);
  }

  @Test
  void shouldJudgeTheLengthOfBytesThatStartLikeAFrame() {
    final byte[] magic = new byte[Integer.BYTES];
    Mcv2Decoder.putUnsignedInt(magic, 0, Mcv2Decoder.MAGIC);
    final String magicMessage = this.message(magic);
    assertEquals(INVALID_LENGTH_MESSAGE, magicMessage);

    // the shortest and the longest frames pass the length check and reach the header's
    final String shortestMessage = this.message(Arrays.copyOf(magic, Mcv2Decoder.HEADER_BYTES));
    assertEquals(NOT_A_FRAME_MESSAGE, shortestMessage);

    final String longestMessage = this.message(Arrays.copyOf(magic, Mcv2Decoder.MAXIMUM_FRAME_BYTES));
    assertEquals(NOT_A_FRAME_MESSAGE, longestMessage);
  }

  private static Stream<Arguments> headerRules() {
    final byte[] key = keyframe(FRAME_SIZE, FRAME_SIZE, solid(1, 2, 3), solid(4, 5, 6), solid(1, 2, 3), solid(7, 8, 9));
    return Stream.of(
      Arguments.of(withWord(key, 4, 0x0104), NOT_A_FRAME_MESSAGE),
      Arguments.of(withWord(key, 4, 0x0303), NOT_A_FRAME_MESSAGE),
      Arguments.of(withWord(key, 4, 0x010103), NOT_A_FRAME_MESSAGE),
      Arguments.of(withWord(key, 4, 0x01000003), NOT_A_FRAME_MESSAGE),
      Arguments.of(withWord(key, 8, (long) FRAME_SIZE << 16), INVALID_DIMENSIONS_MESSAGE),
      Arguments.of(withWord(key, 8, 4097 | ((long) FRAME_SIZE << 16)), INVALID_DIMENSIONS_MESSAGE),
      Arguments.of(withWord(key, 8, FRAME_SIZE), INVALID_DIMENSIONS_MESSAGE),
      Arguments.of(withWord(key, 8, FRAME_SIZE | (4097L << 16)), INVALID_DIMENSIONS_MESSAGE)
    );
  }

  private static byte[] withWord(final byte[] frame, final int offset, final long value) {
    final byte[] changed = frame.clone();
    Mcv2Decoder.putUnsignedInt(changed, offset, value);
    return changed;
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("headerRules")
  void shouldEnforceEveryHeaderRule(final byte[] frame, final String expected) {
    final String failureMessage = this.message(frame);
    assertEquals(expected, failureMessage);
  }

  private String message(final byte[] frame) {
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame));
    return exception.getMessage();
  }
}
