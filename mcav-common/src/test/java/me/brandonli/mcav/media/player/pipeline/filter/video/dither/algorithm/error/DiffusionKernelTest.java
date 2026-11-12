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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DiffusionKernelTest {

  private static final String KERNEL_NAME = "x";

  private static int weightSum(final DiffusionKernel kernel) {
    int sum = 0;
    final int taps = kernel.getTapCount();
    for (int tap = 0; tap < taps; tap++) {
      sum += kernel.getWeight(tap);
    }

    return sum;
  }

  @Test
  void shouldSpreadTheWholeErrorWithConservingKernels() {
    final List<DiffusionKernel> kernels = List.of(
      DiffusionKernel.FLOYD_STEINBERG,
      DiffusionKernel.FILTER_LITE,
      DiffusionKernel.BURKES,
      DiffusionKernel.JARVIS_JUDICE_NINKE,
      DiffusionKernel.STUCKI,
      DiffusionKernel.STEVENSON_ARCE
    );
    for (final DiffusionKernel kernel : kernels) {
      final int sum = weightSum(kernel);
      final int divisor = kernel.getDivisor();
      final String name = kernel.getName();
      assertEquals(divisor, sum, name);
    }
  }

  @Test
  void shouldSpreadThreeQuartersOfTheErrorWithAtkinson() {
    final int sum = weightSum(DiffusionKernel.ATKINSON);
    final int divisor = DiffusionKernel.ATKINSON.getDivisor();
    assertEquals(6, sum);
    assertEquals(8, divisor);
  }

  @Test
  void shouldDescribeItsTaps() {
    final DiffusionKernel kernel = DiffusionKernel.FLOYD_STEINBERG;
    final int taps = kernel.getTapCount();
    final int firstX = kernel.getOffsetX(0);
    final int firstY = kernel.getOffsetY(0);
    final int secondX = kernel.getOffsetX(1);
    final int maximumX = kernel.getMaximumOffsetX();
    final int maximumY = kernel.getMaximumOffsetY();
    final int wideMaximumX = DiffusionKernel.STEVENSON_ARCE.getMaximumOffsetX();
    final int wideMaximumY = DiffusionKernel.STEVENSON_ARCE.getMaximumOffsetY();
    final String name = kernel.getName();
    final String text = kernel.toString();
    assertEquals(4, taps);
    assertEquals(1, firstX);
    assertEquals(0, firstY);
    assertEquals(-1, secondX);
    assertEquals(1, maximumX);
    assertEquals(1, maximumY);
    assertEquals(3, wideMaximumX);
    assertEquals(3, wideMaximumY);
    assertEquals("Floyd-Steinberg", name);
    assertEquals(name, text);
  }

  @Test
  void shouldAcceptCustomKernels() {
    final DiffusionKernel kernel = new DiffusionKernel("Right", 2, new int[][] { { 1, 0, 1 }, { 0, 1, 1 } });
    final int taps = kernel.getTapCount();
    final int maximumX = kernel.getMaximumOffsetX();
    assertEquals(2, taps);
    assertEquals(1, maximumX);
  }

  @Test
  void shouldKeepItsOwnCopyOfTheTaps() {
    final int[] tap = { 1, 0, 1 };
    final int[][] taps = { tap };
    final DiffusionKernel kernel = new DiffusionKernel(KERNEL_NAME, 1, taps);
    tap[0] = 2;
    taps[0] = new int[] { 0, 1, 1 };

    final int offsetX = kernel.getOffsetX(0);
    assertEquals(1, offsetX, "changes to the caller's arrays do not reach the kernel");
  }

  @Test
  void shouldRejectInvalidKernels() {
    final int[][] valid = { { 1, 0, 1 } };
    assertThrows(NullPointerException.class, () -> new DiffusionKernel(null, 1, valid));
    assertThrows(NullPointerException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, null));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 0, valid));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, -1, valid));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[0][]));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, 0 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, 0, 1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 0, -1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, -1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 0, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { -1, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, 0, 0 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, 0, -1 } }));
    assertThrows(NullPointerException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, 0, 1 }, null }));
  }

  // Error rows grow with a kernel's reach, so a far tap would exhaust the heap and Integer.MIN_VALUE would make the reach negative.
  @Test
  void shouldRefuseTapsThatReachFartherThanAnyNeighbourhood() {
    final int limit = DiffusionKernel.MAXIMUM_OFFSET;
    final int[][] widest = { { limit, 0, 1 }, { -limit, limit, 1 } };
    final DiffusionKernel kernel = new DiffusionKernel("widest", 2, widest);
    final int maximumX = kernel.getMaximumOffsetX();
    final int maximumY = kernel.getMaximumOffsetY();

    assertEquals(limit, maximumX);
    assertEquals(limit, maximumY);
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { limit + 1, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { -limit - 1, 1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 0, limit + 1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { Integer.MIN_VALUE, 1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { Integer.MAX_VALUE, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel(KERNEL_NAME, 1, new int[][] { { 1, Integer.MAX_VALUE, 1 } }));
  }
}
