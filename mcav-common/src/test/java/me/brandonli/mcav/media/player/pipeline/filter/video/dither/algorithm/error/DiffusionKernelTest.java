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

/**
 * Tests {@link DiffusionKernel}.
 */
final class DiffusionKernelTest {

  private static int weightSum(final DiffusionKernel kernel) {
    int sum = 0;
    final int taps = kernel.getTapCount();
    for (int tap = 0; tap < taps; tap++) {
      sum += kernel.getWeight(tap);
    }
    return sum;
  }

  @Test
  void conservingKernelsSpreadTheWholeError() {
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
  void atkinsonSpreadsThreeQuartersOfTheError() {
    final int sum = weightSum(DiffusionKernel.ATKINSON);
    final int divisor = DiffusionKernel.ATKINSON.getDivisor();
    assertEquals(6, sum);
    assertEquals(8, divisor);
  }

  @Test
  void describesItsTaps() {
    final DiffusionKernel kernel = DiffusionKernel.FLOYD_STEINBERG;
    final int taps = kernel.getTapCount();
    final int firstX = kernel.getOffsetX(0);
    final int firstY = kernel.getOffsetY(0);
    final int secondX = kernel.getOffsetX(1);
    final int maxX = kernel.getMaxOffsetX();
    final int maxY = kernel.getMaxOffsetY();
    final int wideMaxX = DiffusionKernel.STEVENSON_ARCE.getMaxOffsetX();
    final int wideMaxY = DiffusionKernel.STEVENSON_ARCE.getMaxOffsetY();
    final String name = kernel.getName();
    final String text = kernel.toString();
    assertEquals(4, taps);
    assertEquals(1, firstX);
    assertEquals(0, firstY);
    assertEquals(-1, secondX);
    assertEquals(1, maxX);
    assertEquals(1, maxY);
    assertEquals(3, wideMaxX);
    assertEquals(3, wideMaxY);
    assertEquals("Floyd-Steinberg", name);
    assertEquals(name, text);
  }

  @Test
  void acceptsCustomKernels() {
    final DiffusionKernel kernel = new DiffusionKernel("Right", 2, new int[][] { { 1, 0, 1 }, { 0, 1, 1 } });
    final int taps = kernel.getTapCount();
    final int maxX = kernel.getMaxOffsetX();
    assertEquals(2, taps);
    assertEquals(1, maxX);
  }

  @Test
  void rejectsInvalidKernels() {
    final int[][] valid = { { 1, 0, 1 } };
    assertThrows(NullPointerException.class, () -> new DiffusionKernel(null, 1, valid));
    assertThrows(NullPointerException.class, () -> new DiffusionKernel("x", 1, null));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 0, valid));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[0][]));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 1, 0 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 0, -1, 1 } }));
    // a tap of a previous row is refused even when its column offset alone would look like a later pixel
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 1, -1, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 0, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { -1, 0, 1 } }));
    assertThrows(IllegalArgumentException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 1, 0, 0 } }));
    assertThrows(NullPointerException.class, () -> new DiffusionKernel("x", 1, new int[][] { { 1, 0, 1 }, null }));
  }
}
