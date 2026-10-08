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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.predicted;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.solid;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class Mcv2DecoderTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Decoder.class);
  }

  @Test
  void decodesAKeyframeWithoutAReferenceAndCropsItsEdges() throws Mcv2Exception {
    final byte[] frame = keyframe(3, 2, solid(10, 20, 30));
    final byte[] picture = Mcv2Decoder.decode(frame, null, 99);
    assertArrayEquals(new byte[] { 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30, 10, 20, 30 }, picture);
    assertArrayEquals(picture, Mcv2Decoder.decode(Mcv2Decoder.parse(frame), new byte[1], 5));
  }

  @Test
  void decodesIntoAPictureOfTheFramesSize() throws Mcv2Exception {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(keyframe(3, 2, solid(10, 20, 30)));
    final byte[] fresh = Mcv2Decoder.decode(frame, null, 0);
    final byte[] into = new byte[18];
    Arrays.fill(into, (byte) 99);
    Mcv2Decoder.decode(frame, null, 0, into);
    assertArrayEquals(fresh, into);
    final byte[] small = new byte[17];
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decode(frame, null, 0, small));
    assertArrayEquals(new byte[17], small);
    assertArrayEquals(fresh, Mcv2Decoder.decode(frame, null, 0));
  }

  @Test
  void predictsAPFrameFromItsReference() throws Mcv2Exception {
    final byte[] reference = new byte[2 * 1 * 3];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (index * 10);
    }
    final byte[] frame = predicted(2, 1, motion(1, 0));
    assertArrayEquals(new byte[] { 30, 40, 50, 30, 40, 50 }, Mcv2Decoder.decode(frame, reference, 0));
  }

  @Test
  void decodesTheSamePictureOnAnyNumberOfWorkers() throws Mcv2Exception {
    final String stream = Mcv2Fixtures.digests("conformance").keySet().iterator().next();
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/" + stream));
    try (final ForkJoinPool pool = new ForkJoinPool(4)) {
      byte[] reference = null;
      for (final byte[] data : frames) {
        final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
        final byte[] sequential = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
        final byte[] before = reference;
        for (int count = 1; count <= 4; count++) {
          final byte[] parallel = new byte[sequential.length];
          new Workers(pool, count).forEach(
            frame.getHeight(),
            () -> parallel,
            (picture, row) -> {
              try {
                Mcv2Decoder.decodeRows(frame, before, frame.getReferenceId(), picture, row, row + 1);
              } catch (final Mcv2Exception exception) {
                throw new AssertionError(exception);
              }
            }
          );
          assertArrayEquals(sequential, parallel);
        }
        reference = sequential;
      }
    }
  }

  @Test
  void decodesAPFrameIntoThePictureItIsPredictedFrom() throws Mcv2Exception {
    final String stream = Mcv2Fixtures.digests("conformance").keySet().iterator().next();
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read("conformance/" + stream));
    byte[] reference = null;
    byte[] reused = null;
    for (int index = 0; index < frames.size(); index++) {
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(frames.get(index));
      final byte[] fresh = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
      if (reused == null) {
        reused = new byte[fresh.length];
      }
      Mcv2Decoder.decode(frame, reused, frame.getReferenceId(), reused);
      assertArrayEquals(fresh, reused, "frame " + index + " of " + stream);
      reference = fresh;
    }
  }

  @Test
  void reportsTheFirstFailingLeafBeforeAnyRowsCanDecode() {
    final int count = 300;
    final byte[] descriptors = new byte[count];
    Arrays.fill(descriptors, (byte) Mcv2Decoder.MODE_SOLID);
    descriptors[1] = Mcv2Decoder.MODE_COMPACT;
    descriptors[290] = Mcv2Decoder.MODE_COMPACT;
    final byte[][] records = new byte[count][3];
    records[1] = new byte[] { 15, 0 };
    records[290] = new byte[] { 0 };
    final byte[] frame = Mcv2WireFrames.frame(
      4096,
      96,
      false,
      descriptors,
      records,
      new int[] { count, 0, 0 },
      new byte[0],
      new byte[][] { {}, {}, {} }
    );
    assertEquals("Invalid compact control", assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame)).getMessage());
  }

  @Test
  void refusesAMissingOrMismatchedReference() {
    final byte[] frame = predicted(2, 1, motion(0, 0));
    assertEquals("Reference frame mismatch", assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0)).getMessage());
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[6], 7));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[5], 0));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(new byte[3], null, 0));
  }
}
