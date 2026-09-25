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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The pixel kernels' rounding and motion prediction, where the decoder and the encoder must agree exactly. */
final class ReconstructionTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Reconstruction.class);
  }

  @Test
  void roundsHalvesUpAndClamps() {
    assertEquals(0, Reconstruction.rgb8(-3.0f));
    assertEquals(255, Reconstruction.rgb8(300.0f));
    assertEquals(2, Reconstruction.rgb8(1.5f));
    assertEquals(3, Reconstruction.rgb8(2.5f));
    assertEquals(2, Reconstruction.rgb8(2.49f));
  }

  @Test
  void predictsWithHalfPixelsAndClampedCoordinates() {
    // a 2x2 reference, one channel pattern: 0, 40 / 80, 120 in every channel
    final byte[] reference = { 0, 0, 0, 40, 40, 40, 80, 80, 80, 120, 120, 120 };
    final int[] out = new int[8 * 8 * 3];
    Reconstruction.predict(reference, 2, 2, 0, 0, 8, 1, 1, out);
    // pixel (0,0) at (0.5, 0.5): the average of all four, times four
    assertEquals(240, out[0]);
    Reconstruction.predict(reference, 2, 2, 0, 0, 8, 1, 0, out);
    assertEquals(2 * 40, out[0]);
    Reconstruction.predict(reference, 2, 2, 0, 0, 8, 0, 1, out);
    assertEquals(2 * 80, out[0]);
    Reconstruction.predict(reference, 2, 2, 0, 0, 8, -5, -5, out);
    assertEquals(0, out[0]);
    // pixels beyond the reference clamp to its last row and column
    assertEquals(4 * 120, out[(7 * 8 + 7) * 3]);
    final int[] rounded = new int[8 * 8 * 3];
    Reconstruction.predicted(new int[] { 2, 6, 5 }, 1, rounded);
    assertArrayEquals(new int[] { 1, 2, 1 }, java.util.Arrays.copyOf(rounded, 3));
  }
}
