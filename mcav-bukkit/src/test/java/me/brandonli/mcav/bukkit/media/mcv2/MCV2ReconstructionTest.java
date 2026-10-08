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

final class MCV2ReconstructionTest {

  @Test
  void roundsHalvesUpAndClamps() {
    for (final float[] sample : new float[][] { { -3, 0 }, { 300, 255 }, { 1.5f, 2 }, { 2.5f, 3 }, { 2.49f, 2 } }) {
      assertEquals((int) sample[1], Mcv2Internals.invoke(MCV2.class, null, "rgb8", new Class<?>[] { float.class }, sample[0]));
    }
  }

  @Test
  void predictsWithWholePixelsAndClampedCoordinates() {
    final byte[] reference = { 0, 0, 0, 40, 40, 40, 80, 80, 80, 120, 120, 120 };
    final Kernels kernels = Mcv2Internals.javaKernels();
    final int[] out = new int[8 * 8 * 3];
    kernels.predict(reference, 2, 2, 0, 0, 8, 1, 1, out);
    assertEquals(4 * 120, out[0]);
    kernels.predict(reference, 2, 2, 0, 0, 8, 1, 0, out);
    assertEquals(4 * 40, out[0]);
    kernels.predict(reference, 2, 2, 0, 0, 8, 0, 1, out);
    assertEquals(4 * 80, out[0]);
    kernels.predict(reference, 2, 2, 0, 0, 8, -5, -5, out);
    assertEquals(0, out[0]);
    assertEquals(4 * 120, out[(7 * 8 + 7) * 3]);
  }
}
