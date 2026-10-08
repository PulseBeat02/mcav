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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class MCV2BoundsTest {

  @Test
  void trivialFramesFitForEveryGeometryThrough4096() {
    // All dimensions in one 32-pixel interval share these counts; no pixel data changes the upper bound.
    for (int columns = 1; columns <= 128; columns++) {
      for (int rows = 1; rows <= 128; rows++) {
        final int roots = columns * rows;
        final int groups = (roots + 31) / 32;
        final int directory = (groups + 7) / 8;
        final int walk = (roots + 7) / 8;
        final int solidBytes = 32 + 4 * groups + 4 * directory + 12 + roots + 4 * walk + 3 * roots;
        assertTrue(solidBytes <= 131071);
        final int skipBytes = 32 + 4 * groups + 4 * directory + 12;
        assertTrue(skipBytes <= 2348);
      }
    }
  }

  @Test
  void retriesAndVerifiesTheLargestTrivialFrame() throws Mcv2Exception {
    final int width = 4096;
    final int height = 4096;
    final byte[] picture = new byte[width * height * 3];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int root = (row / 32) * 128 + column / 32;
        final int at = (row * width + column) * 3;
        picture[at] = (byte) root;
        picture[at + 1] = (byte) (root >> 8);
      }
    }
    final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 4, true);
    encoder.setFrameBudget(1);
    encoder.setFrameLimit(1);
    final byte[] first = encoder.encode(picture, width, height, 0);
    assertTrue(first.length <= 131071);
    assertEquals(72 * 16, encoder.getStats().lambda());
    assertEquals(128 * 128, Mcv2Decoder.parse(first).getLeafCount());
    assertArrayEquals(picture, encoder.getReference());
    final byte[] next = encoder.encode(picture, width, height, 1);
    assertEquals(2348, next.length);
    assertEquals(0, Mcv2Decoder.parse(next).getReferenceId());
    assertArrayEquals(picture, encoder.getReference());
  }

  @Test
  void theWriterRequestsARetryForAnOversizedTree() {
    final Node palette = Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, new byte[14]);
    final Node full = Mcv2Trees.split(Mcv2Trees.split(palette));
    final List<Object> roots = Collections.nCopies(128 * 128, full.value());
    assertNull(
      Mcv2Internals.call(
        MCV2.class,
        null,
        "write",
        new Class<?>[] { int.class, int.class, boolean.class, List.class, long.class, long.class },
        4096,
        4096,
        true,
        roots,
        0L,
        0L
      )
    );
  }
}
