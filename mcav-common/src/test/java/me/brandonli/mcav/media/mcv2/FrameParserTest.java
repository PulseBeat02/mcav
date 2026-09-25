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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Frames.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.brandonli.mcav.media.mcv2.encode.TreeNode;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The frame parser: valid frames of every index form expose their header and leaves, every header rule is enforced,
 * and whatever a single corrupted byte does to a valid frame, the parser either accepts the frame or throws
 * {@link Mcv2Exception} and nothing else.
 */
final class FrameParserTest {

  private static final byte[] ENDPOINTS = { (byte) 0x10, (byte) 0x20, (byte) 0x30, (byte) 0xF0, (byte) 0xE0, (byte) 0xD0 };

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FrameParser.class);
  }

  @Test
  void parsesTheHeaderAndTheLeavesOfAKeyframe() throws Mcv2Exception {
    final byte[] frame = keyframe(40, 20, DERIVED, solid(1, 2, 3), split(leaf(Mcv2Format.MODE_PALETTE, 0, 16, 7)));
    final Mcv2Frame parsed = FrameParser.parse(frame);
    assertEquals(40, parsed.getWidth());
    assertEquals(20, parsed.getHeight());
    assertEquals(0, parsed.getFrameId());
    assertEquals(0, parsed.getReferenceId());
    assertTrue(parsed.isKeyframe());
    assertEquals(0x010203, parsed.getDefaultColor());
    assertEquals(0, parsed.getGlobalX());
    assertEquals(0, parsed.getGlobalY());
    assertEquals(frame.length, parsed.getLength());
    assertArrayEquals(frame, parsed.getData());
    assertNotSame(parsed.getData(), parsed.getData());
    assertEquals(5, parsed.getLeafCount());
    final List<Mcv2Frame.Leaf> leaves = new ArrayList<>();
    for (int i = 0; i < parsed.getLeafCount(); i++) {
      leaves.add(parsed.getLeaf(i));
    }
    // level order: the second root's four palettes first; the default-coloured first root is left out of the index
    assertEquals(new Mcv2Frame.Leaf(32, 0, 16, Mcv2Format.MODE_PALETTE, 0, parsed.getPayloadStart()), leaves.get(0));
    assertEquals(new Mcv2Frame.Leaf(0, 0, 32, Mcv2Format.MODE_SKIP, 0, 0), leaves.get(4));
    assertThrows(IndexOutOfBoundsException.class, () -> parsed.getLeaf(5));
  }

  @Test
  void parsesTheGlobalMotionOfAPFrame() throws Mcv2Exception {
    final Mcv2Frame parsed = FrameParser.parse(predicted(32, 32, -3, 7, SHORT, motion(1, 2)));
    assertFalse(parsed.isKeyframe());
    assertEquals(1, parsed.getFrameId());
    assertEquals(-3, parsed.getGlobalX());
    assertEquals(7, parsed.getGlobalY());
    assertEquals(new Mcv2Frame.Leaf(0, 0, 32, Mcv2Format.MODE_IMMEDIATE_MOTION, 0, 0x0201), parsed.getLeaf(0));
  }

  @Test
  void copiesTheInputSoTheCallerMayReuseIt() throws Mcv2Exception {
    final byte[] frame = keyframe(8, 8, DERIVED, solid(9, 9, 9));
    final Mcv2Frame parsed = FrameParser.parse(frame);
    frame[40] = 1;
    assertEquals(0, parsed.getData()[40]);
  }

  @Test
  void exposesCopiesOfTheTables() throws Mcv2Exception {
    final TreeNode patterns = split(pattern(16, ENDPOINTS, 0, 0x5A));
    final Mcv2Frame parsed = FrameParser.parse(keyframe(64, 32, DERIVED, split(split(pattern(8, ENDPOINTS, 1, 0x3C))), patterns));
    final byte[] endpoints = parsed.getEndpointTable();
    assertArrayEquals(ENDPOINTS, endpoints);
    assertNotSame(endpoints, parsed.getEndpointTable());
    assertArrayEquals(new byte[] { 1, 0x3C }, parsed.getSelectorTable(8));
    assertEquals(null, parsed.getSelectorTable(32));
    assertThrows(IllegalArgumentException.class, () -> parsed.getSelectorTable(4));
    final Mcv2Frame plain = FrameParser.parse(keyframe(8, 8, DERIVED_PLAIN, solid(1, 1, 1)));
    assertEquals(null, plain.getEndpointTable());
    assertEquals(null, plain.getSelectorTable(8));
  }

  @Test
  void rejectsBytesThatAreNotAnMcv2Frame() {
    assertThrows(Mcv2Exception.class, () -> FrameParser.parse(new byte[] { 'M', 'C', 'V' }));
    assertThrows(Mcv2Exception.class, () -> FrameParser.parse(new byte[48]));
    final UnsupportedSyntaxException mcv1 = assertThrows(UnsupportedSyntaxException.class, () ->
      FrameParser.parse(new byte[] { 'M', 'C', 'V', '1' })
    );
    assertEquals("MCV1 frames are not supported", mcv1.getMessage());
    assertThrows(NullPointerException.class, () -> FrameParser.parse(null));
  }

  @Test
  void rejectsFramesOfTheWrongLength() {
    final byte[] frame = keyframe(8, 8, DERIVED, solid(1, 1, 1));
    assertEquals("Invalid frame length", message(java.util.Arrays.copyOf(frame, 40)));
    final byte[] huge = new byte[Mcv2Format.MAX_FRAME_BYTES + 1];
    System.arraycopy(frame, 0, huge, 0, 4);
    assertEquals("Invalid frame length", message(huge));
  }

  static Stream<Arguments> headerRules() {
    final byte[] key = keyframe(40, 40, DERIVED, solid(1, 2, 3), solid(4, 5, 6), solid(1, 2, 3), solid(7, 8, 9));
    final byte[] motion = predicted(40, 40, 2, 0, SHORT, motion(1, 1), motion(1, 1), motion(1, 1), motion(1, 1));
    final int keyFlags = flagsOf(key);
    return Stream.of(
      Arguments.of(withWord(key, 4, 0x0503 | ((long) keyFlags << 16)), "Unsupported MCV2 header"),
      Arguments.of(withWord(key, 4, 0x0602 | ((long) keyFlags << 16)), "Unsupported MCV2 header"),
      Arguments.of(withFlags(key, keyFlags | 16384), "Unsupported MCV2 header"),
      Arguments.of(withWord(key, 40, 1), "Unsupported MCV2 header"),
      Arguments.of(withWord(key, 44, 1), "Unsupported MCV2 header"),
      Arguments.of(withWord(key, 8, 0 | (40L << 16)), "Invalid dimensions or total"),
      Arguments.of(withWord(key, 8, 4097 | (40L << 16)), "Invalid dimensions or total"),
      Arguments.of(withWord(key, 8, 40), "Invalid dimensions or total"),
      Arguments.of(withWord(key, 8, 40 | (4097L << 16)), "Invalid dimensions or total"),
      Arguments.of(withWord(key, 32, key.length + 1L), "Invalid dimensions or total"),
      Arguments.of(withWord(key, 24, 5), "Invalid root table"),
      Arguments.of(withWord(key, 28, 47), "Invalid root table"),
      Arguments.of(withWord(key, 28, key.length + 1L), "Invalid root table"),
      Arguments.of(withWord(key, 16, 5), "Invalid reference metadata"),
      Arguments.of(withWord(key, 20, 2), "Invalid reference metadata"),
      Arguments.of(withWord(motion, 16, 1), "Invalid reference metadata"),
      Arguments.of(withWord(key, 36, 0x1000000), "Invalid default color"),
      Arguments.of(withFlags(withWord(motion, 36, 0), flagsOf(motion) | Mcv2Format.DEFAULT_SOLID), "Invalid default color"),
      Arguments.of(withFlags(key, keyFlags & ~Mcv2Format.DEFAULT_SOLID), "Invalid default color"),
      Arguments.of(withFlags(motion, flagsOf(motion) | Mcv2Format.TWO_LEVEL_WALK), "Two-level walk requires derived offsets"),
      Arguments.of(withFlags(motion, flagsOf(motion) | Mcv2Format.PACKED_SYMBOLS), "Packed symbols require derived offsets"),
      Arguments.of(withFlags(key, keyFlags & ~Mcv2Format.SPARSE), "Derived offsets require the level-ordered sparse form"),
      Arguments.of(withFlags(key, keyFlags & ~Mcv2Format.DERIVED_DIRECTORY), "Derived offsets require the level-ordered sparse form"),
      Arguments.of(withFlags(key, keyFlags | Mcv2Format.SHORT_INDEX), "Derived offsets require the level-ordered sparse form")
    );
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("headerRules")
  void enforcesEveryHeaderRule(final byte[] frame, final String expected) {
    assertEquals(expected, message(frame));
  }

  @Test
  void rejectsAShortIndexFrameBeyondSixteenBitAddresses() {
    final byte[] frame = predicted(32, 32, 0, 0, SHORT, motion(1, 1));
    final byte[] padded = java.util.Arrays.copyOf(frame, 65536);
    Mcv2Format.putU32(padded, 32, padded.length);
    assertEquals("Short index frame exceeds the address range", message(padded));
  }

  @Test
  void rejectsTheMotionTableOfRound15() {
    final byte[] frame = predicted(32, 32, 0, 0, SHORT, motion(1, 1));
    final UnsupportedSyntaxException exception = assertThrows(UnsupportedSyntaxException.class, () ->
      FrameParser.parse(withFlags(frame, flagsOf(frame) | Mcv2Format.MOTION_TABLE))
    );
    assertEquals("The motion table of round 15 is not supported", exception.getMessage());
  }

  static int flagsOf(final byte[] frame) {
    return (int) (Mcv2Format.u32(frame, 4) >>> 16);
  }

  static String message(final byte[] frame) {
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> FrameParser.parse(frame));
    return exception.getMessage();
  }
}
