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
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.mcv2.encode.TreeNode;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The decoder's contract around the pixel arithmetic: which reference a frame needs, and cropped edges. */
final class Mcv2DecoderTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Decoder.class);
  }

  @Test
  void decodesAKeyframeWithoutAReferenceAndCropsItsEdges() throws Mcv2Exception {
    final byte[] frame = keyframe(3, 2, DERIVED, solid(10, 20, 30));
    final byte[] picture = Mcv2Decoder.decode(frame, null, 99);
    assertArrayEquals(new byte[] { 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30 }, picture);
    assertArrayEquals(picture, Mcv2Decoder.decode(FrameParser.parse(frame), new byte[1], 5));
  }

  @Test
  void predictsAPFrameFromItsReference() throws Mcv2Exception {
    final byte[] reference = new byte[2 * 1 * 3];
    for (int i = 0; i < reference.length; i++) {
      reference[i] = (byte) (i * 10);
    }
    // global motion of one whole pixel to the right: pixel 0 samples pixel 1, pixel 1 is clamped to itself
    final byte[] frame = predicted(2, 1, 2, 0, SHORT, TreeNode.skip());
    assertArrayEquals(new byte[] { 30, 40, 50, 30, 40, 50 }, Mcv2Decoder.decode(frame, reference, 0));
  }

  @Test
  void refusesAMissingOrMismatchedReference() {
    final byte[] frame = predicted(2, 1, 0, 0, SHORT, motion(0, 0));
    assertEquals("Reference frame mismatch", assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0)).getMessage());
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[6], 7));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[5], 0));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(new byte[3], null, 0));
  }
}
