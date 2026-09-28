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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.bukkit.media.mcv2.ReconstructionOracle;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The least-squares grid fits: a field the decoder can reconstruct exactly is fitted back to its own nodes. */
final class FitsTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Fits.class);
  }

  @Test
  void recoversTheNodesOfAnInterpolatedField() {
    for (int size = 8; size <= 32; size *= 2) {
      for (int grid = 1; grid <= 8; grid *= 2) {
        final float[] nodes = new float[grid * grid];
        for (int nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
          nodes[nodeIndex] = ((nodeIndex * 37) % 11) - 5;
        }
        // the field sits in the second of three interleaved channels
        final float[] values = new float[size * size * 3];
        for (int row = 0; row < size; row++) {
          for (int column = 0; column < size; column++) {
            values[(row * size + column) * 3 + 1] = (float) ReconstructionOracle.interpolate(nodes, 0, 1, grid, size, column, row);
          }
        }
        final float[] fitted = new float[grid * grid * 2];
        Fits.fit(values, 1, 3, size, grid, new double[size * grid], fitted, 1, 2);
        for (int nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
          assertEquals(nodes[nodeIndex], fitted[nodeIndex * 2 + 1], 1e-3, "size " + size + " grid " + grid + " node " + nodeIndex);
        }
      }
    }
  }
}
