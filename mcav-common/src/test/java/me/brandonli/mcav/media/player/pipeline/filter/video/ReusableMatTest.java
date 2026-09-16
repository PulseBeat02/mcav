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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ReusableMat}.
 */
final class ReusableMatTest {

  private static final int THREADS = 4;
  private static final int ROUNDS = 2_000;

  @Test
  void lendsTheSameMatrixFrameAfterFrame() {
    final ReusableMat reusable = new ReusableMat();
    final Mat first = reusable.borrow();
    final boolean kept = reusable.giveBack(first);
    final Mat second = reusable.borrow();
    assertTrue(kept);
    assertSame(first, second, "a matrix handed back is lent again instead of allocating a new one");
    reusable.giveBack(second);
  }

  @Test
  void lendsAnotherMatrixWhileTheFirstIsInUseAndFreesTheSurplus() {
    final ReusableMat reusable = new ReusableMat();
    final Mat first = reusable.borrow();
    final Mat second = reusable.borrow();
    final boolean firstKept = reusable.giveBack(first);
    final boolean secondKept = reusable.giveBack(second);
    final boolean secondFreed = second.isNull();
    final Mat next = reusable.borrow();
    assertNotSame(first, second, "a matrix that is lent out is never lent to a second caller");
    assertTrue(firstKept);
    assertFalse(secondKept);
    assertTrue(secondFreed, "a matrix that is not kept is freed right away");
    assertSame(first, next);
    reusable.giveBack(next);
  }

  @Test
  void neverLendsOneMatrixToTwoThreadsAtOnce() throws Exception {
    final ReusableMat reusable = new ReusableMat();
    final List<Future<Integer>> results = new ArrayList<>();
    try (final ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
      for (int thread = 0; thread < THREADS; thread++) {
        final byte marker = (byte) (thread + 1);
        final Future<Integer> result = executor.submit(() -> countForeignWrites(reusable, marker));
        results.add(result);
      }
      for (final Future<Integer> result : results) {
        final int foreignWrites = result.get();
        assertEquals(0, foreignWrites, "every borrower sees only its own writes");
      }
    }
  }

  /**
   * Borrows the matrix many times, writes a marker into it, pauses briefly while the other threads run, and counts how often the
   * marker was overwritten before the matrix was handed back, which would mean two threads used it at once.
   */
  private static int countForeignWrites(final ReusableMat reusable, final byte marker) {
    int foreignWrites = 0;
    for (int round = 0; round < ROUNDS; round++) {
      final Mat matrix = reusable.borrow();
      matrix.create(1, 1, opencv_core.CV_8UC1);
      final ByteBuffer pixel = matrix.createBuffer();
      pixel.put(0, marker);
      Thread.onSpinWait();
      final byte seen = pixel.get(0);
      if (seen != marker) {
        foreignWrites++;
      }
      reusable.giveBack(matrix);
    }
    return foreignWrites;
  }
}
