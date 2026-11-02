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

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Kernels;
import org.junit.jupiter.api.Test;

final class Mcv2ReconstructionTest {

  @Test
  void shouldRoundHalvesUpAndClamp() {
    for (final int[] sample : new int[][] { { -768, 0 }, { 76800, 255 }, { 384, 2 }, { 640, 3 }, { 637, 2 } }) {
      final int rounded = Mcv2Decoder.roundToChannel(sample[0], 8);
      assertEquals(sample[1], rounded);
    }
  }

  @Test
  void shouldPredictWithWholePixelsAndClampedCoordinates() {
    final byte[] reference = { 0, 0, 0, 40, 40, 40, 80, 80, 80, 120, 120, 120 };
    final Kernels kernels = Mcv2Internals.javaKernels();
    final int[] out = new int[8 * 8 * 3];
    kernels.predict(reference, 2, 2, 0, 0, 8, 1, 1, out);
    final int shiftedDiagonally = out[0];
    assertEquals(120, shiftedDiagonally);

    kernels.predict(reference, 2, 2, 0, 0, 8, 1, 0, out);
    final int shiftedRight = out[0];
    assertEquals(40, shiftedRight);

    kernels.predict(reference, 2, 2, 0, 0, 8, 0, 1, out);
    final int shiftedDown = out[0];
    assertEquals(80, shiftedDown);

    kernels.predict(reference, 2, 2, 0, 0, 8, -5, -5, out);
    final int clampedFirst = out[0];
    assertEquals(0, clampedFirst);

    final int clampedLast = out[(7 * 8 + 7) * 3];
    assertEquals(120, clampedLast);
  }
}
