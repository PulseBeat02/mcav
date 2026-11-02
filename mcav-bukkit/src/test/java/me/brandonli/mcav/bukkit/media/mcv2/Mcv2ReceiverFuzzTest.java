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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2ReceiverFuzzTest {

  private static final int MAXIMUM_FRAMES = 8;
  private static final int THREADS = 3;
  private static final ForkJoinPool POOL = new ForkJoinPool(THREADS);

  @FuzzTest(maxDuration = "30s")
  void shouldCommitOnlyFramesThatDecodeAndDecodeThemAlikeOnAnyWorkers(final byte[] archive) {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final Mcv2Receiver committedOnly = new Mcv2Receiver();
    byte @Nullable [] parallelPicture = null;
    long parallelId = -1;
    for (final byte[] frame : frames(archive)) {
      final long lastId = receiver.getFrameId();
      final byte[] picture;
      try {
        picture = receiver.accept(frame);
      } catch (final Mcv2Exception exception) {
        final long frameIdAfterRefusal = receiver.getFrameId();
        assertEquals(lastId, frameIdAfterRefusal, "a refused frame keeps the last frame");

        continue;
      }
      final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
      final int expectedLength = parsed.getWidth() * parsed.getHeight() * 3;
      final int pictureLength = picture.length;
      assertEquals(expectedLength, pictureLength, "the picture has the size of its frame");
      final long expectedFrameId = parsed.getFrameId();
      final long frameId = receiver.getFrameId();
      assertEquals(expectedFrameId, frameId, "a committed frame is the last frame");
      final byte[] committedPicture = committedOnly.accept(frame);
      assertArrayEquals(picture, committedPicture, "a refused frame left nothing behind in the receiver");

      final byte[] prediction = parallelPicture;
      final byte[] rows = new byte[picture.length];
      final long predictsId = parallelId;
      POOL.submit(() -> this.decodeBands(parsed, prediction, predictsId, rows)).join();
      assertArrayEquals(picture, rows, "parallel bands decode the same picture");
      if (parallelPicture == null || parallelPicture.length != picture.length) {
        parallelPicture = new byte[picture.length];
      }
      Mcv2Decoder.decode(parsed, prediction, parallelId, parallelPicture);
      parallelId = parsed.getFrameId();
      assertArrayEquals(picture, parallelPicture, "the workers decode the picture the receiver decodes");
    }
  }

  private void decodeBands(final Mcv2Decoder.Frame parsed, final byte @Nullable [] prediction, final long predictsId, final byte[] rows) {
    IntStream.range(0, THREADS)
      .parallel()
      .forEach(band -> this.decodeBand(parsed, prediction, predictsId, rows, band));
  }

  private void decodeBand(
    final Mcv2Decoder.Frame parsed,
    final byte @Nullable [] prediction,
    final long predictsId,
    final byte[] rows,
    final int band
  ) {
    final int from = (band * parsed.getHeight()) / THREADS;
    final int to = ((band + 1) * parsed.getHeight()) / THREADS;
    assertDoesNotThrow(() -> Mcv2Decoder.decodeRows(parsed, prediction, predictsId, rows, from, to));
  }

  private static List<byte[]> frames(final byte[] archive) {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset + 4 <= archive.length && frames.size() < MAXIMUM_FRAMES) {
      final long length = Mcv2Decoder.readUnsignedInt(archive, offset);
      final int start = offset + 4;
      final int end = (int) Math.min(archive.length, start + length);
      frames.add(Arrays.copyOfRange(archive, start, end));
      offset = end;
    }

    return frames;
  }
}
