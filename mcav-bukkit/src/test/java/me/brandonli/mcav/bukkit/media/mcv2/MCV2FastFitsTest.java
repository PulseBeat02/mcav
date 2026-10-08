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

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The cheap fits of the live search: cell means for grids, a short integer clustering for palettes. */
final class MCV2FastFitsTest {

  @ParameterizedTest
  @ValueSource(ints = { 8, 16, 32 })
  void clustersTwoColours(final int size) {
    final int[] source = new int[size * size * 3];
    for (int pixel = 0; pixel < size * size; pixel++) {
      final boolean bright = pixel % size >= size / 2;
      source[pixel * 3] = bright ? 200 : 10;
      source[pixel * 3 + 1] = bright ? 210 : 20;
      source[pixel * 3 + 2] = bright ? 220 : 30;
    }
    final float[] endpoints = new float[6];
    Mcv2Internals.javaKernels().cluster(source, size, endpoints);
    assertArrayEquals(new float[] { 10, 20, 30, 200, 210, 220 }, endpoints);
  }

  @Test
  void keepsTheSeedsOfAnEmptyCluster() {
    // one colour everywhere: both seeds are that colour, every pixel joins the first, the second keeps its seed
    final int[] source = new int[8 * 8 * 3];
    Arrays.fill(source, 77);
    final float[] endpoints = new float[6];
    Mcv2Internals.javaKernels().cluster(source, 8, endpoints);
    assertArrayEquals(new float[] { 77, 77, 77, 77, 77, 77 }, endpoints);
  }
}
