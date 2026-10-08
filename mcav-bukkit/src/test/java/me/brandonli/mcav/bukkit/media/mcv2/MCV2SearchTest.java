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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class MCV2SearchTest {

  private static int vector(final int motionX, final int motionY) {
    return (motionX << 16) | (motionY & 65535);
  }

  private static void fillMotion(
    final int[] field,
    final int width,
    final int height,
    final Node node,
    final int left,
    final int top,
    final int size
  ) {
    Mcv2Internals.call(
      MCV2.class,
      null,
      "fillMotion",
      new Class<?>[] { int[].class, int.class, int.class, Mcv2Internals.nested("TreeNode"), int.class, int.class, int.class },
      field,
      width,
      height,
      node.value(),
      left,
      top,
      size
    );
  }

  @Test
  void findsEachLeafsVector() {
    final Node[] nodes = {
      Node.skip(),
      Mcv2Trees.solid(1, 2, 3),
      Mcv2Trees.motion(3, -4),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 0, 0, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 2, -2, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 20, -30, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
    };
    final int[] expected = { 0, 0, vector(3, -4), 0, vector(2, -2), vector(20, -30) };
    for (int index = 0; index < nodes.length; index++) {
      final int[] field = new int[1];
      fillMotion(field, 8, 8, nodes[index], 0, 0, 8);
      assertEquals(expected[index], field[0]);
    }
  }

  @Test
  void keepsOneVectorPerEightPixelCellOfTheLeaves() {
    final Node split = Node.split(Mcv2Trees.motion(4, 0), Node.skip(), Node.skip(), Node.skip());
    final int[] field = new int[5 * 3];
    fillMotion(field, 40, 20, split, 0, 0, 32);
    fillMotion(field, 40, 20, Mcv2Trees.solid(0, 0, 0), 32, 0, 32);
    assertArrayEquals(new int[] { vector(4, 0), vector(4, 0), 0, 0, 0, vector(4, 0), vector(4, 0), 0, 0, 0, 0, 0, 0, 0, 0 }, field);
  }

  @Test
  void seedsTheLocalSearchFromThePreviousFrame() {
    final byte[] reference = Mcv2Pictures.scene(64, 64, 0, 0);
    final byte[] source = Mcv2Pictures.scene(64, 64, 2, 3);
    final int[] field = new int[8 * 8];
    Arrays.fill(field, vector(6, 0));
    final Mcv2BlockState job = new Mcv2BlockState(source, reference, 64, 64, false, false, 72, field);
    assertEquals(vector(6, 0), job.previousMotion(-5, 100));
    final Mcv2BlockState fresh = new Mcv2BlockState(source, reference, 64, 64, false, false, 72, null);
    assertEquals(0, fresh.previousMotion(10, 10));
    job.code(16, 1, 0, 0, 0, vector(6, 0));
    assertTrue(job.cost(1, 0) < Double.POSITIVE_INFINITY);
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        field[row * 8 + column] = vector(2 * column, -2 * row);
      }
    }
    for (final int parent : new int[] { Mcv2BlockState.NO_VECTOR, vector(-6, -4) }) {
      final Mcv2BlockState seeded = new Mcv2BlockState(source, reference, 64, 64, false, false, 0, field);
      final Object coder = seeded.code(16, 1, 5, 16, 16, parent);
      assertArrayEquals(
        new int[] {
          vector(3, -3),
          vector(1, -3),
          vector(4, -3),
          vector(3, -1),
          vector(3, -4),
          parent == Mcv2BlockState.NO_VECTOR ? vector(3, -3) : vector(-3, -2),
        },
        Mcv2BlockState.seeds(coder, "halfSeeds")
      );
    }
  }

  @Test
  void skipsEarlyExactlyAtTheThresholdAndMonotonicallyInLambda() {
    final byte[] reference = Mcv2Pictures.scene(32, 32, 0, 0);
    for (final boolean fast : new boolean[] { false, true }) {
      for (int amplitude = 0; amplitude <= 12; amplitude += 2) {
        final Random random = new Random(amplitude);
        final byte[] source = reference.clone();
        for (int index = 0; index < source.length; index++) {
          source[index] = (byte) Math.clamp((source[index] & 255) + random.nextInt(2 * amplitude + 1) - amplitude, 0, 255);
        }
        final long distortion = MCV2SearchPropertyTest.distortion(source, reference);
        boolean skippedBefore = false;
        for (final double lambda : new double[] { 10, 30, 65.255994022, 150, 400, 1200 }) {
          final Mcv2BlockState job = new Mcv2BlockState(source, reference, 32, 32, false, fast, lambda, null);
          final Object coder = job.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
          final boolean skipped = Mcv2BlockState.skipped(coder);
          assertEquals(distortion / 96.0 + lambda <= (fast ? 60 : 28) * lambda, skipped);
          assertTrue(!skippedBefore || skipped, "monotonic in lambda");
          skippedBefore = skipped;
          if (skipped) {
            assertEquals(Mcv2Decoder.MODE_SKIP, job.mode(0, 0));
          }
        }
      }
    }
  }

  @Test
  void halvesAPictureWithItsOddLastRowAndColumnStandingAlone() {
    final int[] red = { 0, 4, 9, 8, 12, 20, 100, 200, 255 };
    final byte[] picture = new byte[3 * 3 * 3];
    for (int index = 0; index < red.length; index++) {
      picture[index * 3] = (byte) red[index];
      picture[index * 3 + 1] = (byte) index;
    }
    assertArrayEquals(
      new byte[] { 6, 2, 0, 15, 4, 0, (byte) 150, 7, 0, (byte) 255, 8, 0 },
      Mcv2BlockState.half(picture, 3, 3, new byte[2 * 2 * 3])
    );
  }
}
