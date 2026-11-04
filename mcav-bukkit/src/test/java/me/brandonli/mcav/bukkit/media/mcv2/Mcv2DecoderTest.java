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
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2DecoderTest {

  private static final int SOLID_RED = 10;
  private static final int SOLID_GREEN = 20;
  private static final int SOLID_BLUE = 30;

  private static final String CONFORMANCE_FOLDER = "conformance";
  private static final String CONFORMANCE_PREFIX = "conformance/";

  @Test
  void shouldBeAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Decoder.class);
  }

  @Test
  void shouldDecodeAKeyframeWithoutAReferenceAndCropItsEdges() {
    final byte[] frame = keyframe(3, 2, solid(SOLID_RED, SOLID_GREEN, SOLID_BLUE));
    final byte[] picture = Mcv2Decoder.decode(frame, null, 99);
    final byte[] expected = new byte[] {
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
      SOLID_RED,
      SOLID_GREEN,
      SOLID_BLUE,
    };
    assertArrayEquals(expected, picture);

    final byte[] decodedFromParsed = Mcv2Decoder.decode(Mcv2Decoder.parse(frame), new byte[1], 5);
    assertArrayEquals(picture, decodedFromParsed);
  }

  @Test
  void shouldDecodeIntoAPictureOfTheFramesSize() {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(keyframe(3, 2, solid(SOLID_RED, SOLID_GREEN, SOLID_BLUE)));
    final byte[] fresh = Mcv2Decoder.decode(frame, null, 0);
    final byte[] into = new byte[18];
    Arrays.fill(into, (byte) 99);
    Mcv2Decoder.decode(frame, null, 0, into);
    assertArrayEquals(fresh, into);

    final byte[] small = new byte[17];
    assertThrows(IllegalArgumentException.class, () -> Mcv2Decoder.decode(frame, null, 0, small));

    final byte[] expectedSmall = new byte[17];
    assertArrayEquals(expectedSmall, small);

    final byte[] decodedAgain = Mcv2Decoder.decode(frame, null, 0);
    assertArrayEquals(fresh, decodedAgain);
  }

  @Test
  void shouldPredictAPFrameFromItsReference() {
    final byte[] reference = new byte[2 * 1 * 3];
    for (int index = 0; index < reference.length; index++) {
      reference[index] = (byte) (index * 10);
    }

    final byte[] frame = predicted(2, 1, motion(1, 0));
    final byte[] expected = new byte[] { 30, 40, 50, 30, 40, 50 };
    final byte[] decoded = Mcv2Decoder.decode(frame, reference, 0);
    assertArrayEquals(expected, decoded);
  }

  @Test
  void shouldDecodeTheSamePictureOnAnyNumberOfWorkers() {
    final String stream = Mcv2Fixtures.readDigests(CONFORMANCE_FOLDER).keySet().iterator().next();
    final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(CONFORMANCE_PREFIX + stream));
    try (final ForkJoinPool pool = new ForkJoinPool(4)) {
      byte[] reference = null;
      for (final byte[] data : frames) {
        final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
        final byte[] sequential = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
        this.assertSameOnEveryWorkerCount(pool, frame, reference, sequential);
        reference = sequential;
      }
    }
  }

  private void assertSameOnEveryWorkerCount(
    final ForkJoinPool pool,
    final Mcv2Decoder.Frame frame,
    final byte @Nullable [] reference,
    final byte[] sequential
  ) {
    for (int count = 1; count <= 4; count++) {
      final byte[] parallel = new byte[sequential.length];
      new Workers(pool, count).forEach(frame.getHeight(), () -> parallel, (picture, row) -> this.decodeRow(frame, reference, picture, row));
      assertArrayEquals(sequential, parallel);
    }
  }

  private void decodeRow(final Mcv2Decoder.Frame frame, final byte @Nullable [] reference, final byte[] picture, final int row) {
    Mcv2Decoder.decodeRows(frame, reference, frame.getReferenceId(), picture, row, row + 1);
  }

  @Test
  void shouldDecodeAPFrameIntoThePictureItIsPredictedFrom() {
    final String stream = Mcv2Fixtures.readDigests(CONFORMANCE_FOLDER).keySet().iterator().next();
    final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(CONFORMANCE_PREFIX + stream));
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
  void shouldReportTheFirstFailingLeafBeforeAnyRowsCanDecode() {
    final int count = 300;
    final byte[] descriptors = new byte[count];
    Arrays.fill(descriptors, (byte) Mcv2Decoder.MODE_SOLID);
    descriptors[1] = Mcv2Decoder.MODE_PATTERN;
    descriptors[290] = Mcv2Decoder.MODE_COMPACT;
    final byte[][] records = new byte[count][3];
    records[1] = new byte[11];
    records[1][6] = 2;
    records[290] = new byte[] { 0 };
    final byte[] frame = Mcv2WireFrames.frame(4096, 96, Mcv2FrameKind.PREDICTED, descriptors, records, new int[] { count, 0, 0 });
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(frame));
    final String message = exception.getMessage();
    assertEquals("Invalid pattern selector!", message);
  }

  @Test
  void shouldRefuseAMissingOrMismatchedReference() {
    final byte[] frame = predicted(2, 1, motion(0, 0));
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, null, 0));
    final String message = exception.getMessage();
    assertEquals("Reference frame mismatch!", message);

    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[6], 7));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(frame, new byte[5], 0));
    assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.decode(new byte[3], null, 0));
  }

  @Test
  void shouldCompareFramesByTheirBytes() {
    final byte[] keyframe = keyframe(3, 2, solid(SOLID_RED, SOLID_GREEN, SOLID_BLUE));
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(keyframe);
    final Mcv2Decoder.Frame equalFrame = Mcv2Decoder.parse(keyframe.clone());
    final Mcv2Decoder.Frame differentFrame = Mcv2Decoder.parse(predicted(2, 1, motion(0, 0)));
    EqualityAssertions.assertEqualityContract(frame, equalFrame, differentFrame);
  }

  @Test
  void shouldDescribeTheSizeAndTheIdsOfAFrame() {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(predicted(2, 1, motion(0, 0)));
    final String description = frame.toString();
    assertEquals("Frame{width=2, height=1, frameId=1, referenceId=0, keyframe=false}", description);
  }
}
