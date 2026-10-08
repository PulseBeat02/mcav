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

/**
 * Fuzzes a receiver with whole streams, the way a client sees them: frames that arrive in any order, repeat, go
 * missing, predict from a frame of another size or id, or are damaged. Whatever the frames, the receiver commits a frame
 * only when it decodes, to a picture of the frame's size, and otherwise throws {@link Mcv2Exception} and keeps its state
 * - so a second receiver that sees only the frames the first one committed decodes the very same pictures. The decoder
 * on several workers, writing into the picture it predicts from, decodes the same pictures as well.
 *
 * <p>An input is a research archive: every frame is preceded by its length as four little-endian bytes, cut short where
 * the input ends. The seeds are the first frames of every committed edge stream, which together use every v3 mode and boundary geometry, and the six frames of the tiny stream in their order.
 */
@Tag("fuzz")
final class Mcv2ReceiverFuzzTest {

  private static final int MAX_FRAMES = 8;
  private static final int THREADS = 3;
  private static final ForkJoinPool POOL = new ForkJoinPool(THREADS);

  @FuzzTest(maxDuration = "30s")
  void commitsOnlyFramesThatDecodeAndDecodesThemAlikeOnAnyWorkers(final byte[] archive) throws Mcv2Exception {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final Mcv2Receiver committedOnly = new Mcv2Receiver();
    byte @Nullable [] parallelPicture = null;
    long parallelId = -1;
    for (final byte[] frame : frames(archive)) {
      final long lastId = receiver.getFrameId();
      final byte[] picture;
      try {
        picture = receiver.accept(frame);
      } catch (final Mcv2Exception refused) {
        assertEquals(lastId, receiver.getFrameId(), "a refused frame keeps the last frame");
        continue;
      }
      final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(frame);
      assertEquals(parsed.getWidth() * parsed.getHeight() * 3, picture.length, "the picture has the size of its frame");
      assertEquals(parsed.getFrameId(), receiver.getFrameId(), "a committed frame is the last frame");
      assertArrayEquals(picture, committedOnly.accept(frame), "a refused frame left nothing behind in the receiver");
      final byte[] prediction = parallelPicture;
      final byte[] rows = new byte[picture.length];
      final long predictsId = parallelId;
      POOL.submit(() ->
        IntStream.range(0, THREADS)
          .parallel()
          .forEach(band -> {
            final int from = (band * parsed.getHeight()) / THREADS;
            final int to = ((band + 1) * parsed.getHeight()) / THREADS;
            assertDoesNotThrow(() -> Mcv2Decoder.decodeRows(parsed, prediction, predictsId, rows, from, to));
          })
      ).join();
      assertArrayEquals(picture, rows, "parallel bands decode the same picture");
      if (parallelPicture == null || parallelPicture.length != picture.length) {
        parallelPicture = new byte[picture.length];
      }
      Mcv2Decoder.decode(parsed, prediction, parallelId, parallelPicture);
      parallelId = parsed.getFrameId();
      assertArrayEquals(picture, parallelPicture, "the workers decode the picture the receiver decodes");
    }
  }

  private static List<byte[]> frames(final byte[] archive) {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset + 4 <= archive.length && frames.size() < MAX_FRAMES) {
      final long length = Mcv2Decoder.u32(archive, offset);
      final int start = offset + 4;
      final int end = (int) Math.min(archive.length, start + length);
      frames.add(Arrays.copyOfRange(archive, start, end));
      offset = end;
    }
    return frames;
  }
}
