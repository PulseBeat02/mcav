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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
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
  void decodesIntoAPictureOfTheFramesSize() throws Mcv2Exception {
    final Mcv2Frame frame = FrameParser.parse(keyframe(3, 2, DERIVED, solid(10, 20, 30)));
    final byte[] fresh = Mcv2Decoder.decode(frame, null, 0);
    // a picture of the frame's size is decoded into, whatever it held; any other is left and a new one made
    final byte[] into = new byte[18];
    Arrays.fill(into, (byte) 99);
    assertSame(into, Mcv2Decoder.decode(frame, null, 0, Workers.SEQUENTIAL, into));
    assertArrayEquals(fresh, into);
    final byte[] small = new byte[17];
    final byte[] made = Mcv2Decoder.decode(frame, null, 0, Workers.SEQUENTIAL, small);
    assertNotSame(small, made);
    assertArrayEquals(fresh, made);
    assertArrayEquals(fresh, Mcv2Decoder.decode(frame, null, 0, Workers.SEQUENTIAL, null));
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
  void decodesTheSamePictureOnAnyNumberOfWorkers() throws Mcv2Exception {
    final String stream = Mcv2Fixtures.digests("conformance").keySet().iterator().next();
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/" + stream));
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      final Workers workers = new Workers(pool, 4);
      byte[] reference = null;
      for (final byte[] data : frames) {
        final Mcv2Frame frame = FrameParser.parse(data);
        final byte[] sequential = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
        assertArrayEquals(sequential, Mcv2Decoder.decode(frame, reference, frame.getReferenceId(), workers));
        reference = sequential;
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A frame the parser would refuse, built directly: 300 compact leaves on a 2400x8 keyframe, where leaf 1 has an
   * invalid control byte and leaf 290, in another group of leaves, is truncated. Whichever group a worker finishes
   * first, the decode reports the failure a sequential decode meets first.
   */
  @Test
  void reportsTheFirstFailingLeafWhateverTheOrderOfTheWorkers() {
    final int leaves = 300;
    final byte[] data = new byte[64];
    // 0x0F is class 15, which does not exist; the last byte starts a record the frame has no room for
    data[10] = 0x0F;
    final int[] array = new int[leaves * Mcv2Frame.LEAF_INTS];
    for (int i = 0; i < leaves; i++) {
      final int at = i * Mcv2Frame.LEAF_INTS;
      array[at] = i * 8;
      array[at + 1] = 0;
      array[at + 2] = 8;
      array[at + 3] = i == 1 || i == 290 ? Mcv2Format.MODE_COMPACT : Mcv2Format.MODE_SOLID;
      array[at + 4] = 0;
      array[at + 5] = i == 1 ? 10 : i == 290 ? data.length - 1 : 0;
    }
    data[data.length - 1] = 0x03;
    final Mcv2Frame frame = new Mcv2Frame(
      data,
      new Mcv2Frame.Header(leaves * 8, 8, 1, 1, Mcv2Format.KEYFRAME, 0, 0, 48, 0),
      array,
      null,
      null
    );
    final ForkJoinPool pool = new ForkJoinPool(4);
    try {
      for (final Workers workers : new Workers[] { Workers.SEQUENTIAL, new Workers(pool, 4) }) {
        final Mcv2Exception failure = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0, workers));
        assertEquals("Invalid compact class or control", failure.getMessage());
      }
    } finally {
      pool.shutdownNow();
    }
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
