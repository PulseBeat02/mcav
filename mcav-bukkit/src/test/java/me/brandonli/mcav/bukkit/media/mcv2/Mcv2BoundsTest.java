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
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2BoundsTest {

  private static final int ROOTS_PER_SIDE = 128;

  private static final int LARGEST_SIDE = 4096;

  private static final int THREADS = 4;

  @Test
  void shouldRetryAndVerifyTheLargestTrivialFrame() {
    final int width = LARGEST_SIDE;
    final int height = LARGEST_SIDE;
    final byte[] picture = new byte[width * height * 3];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int root = (row / 32) * ROOTS_PER_SIDE + column / 32;
        final int offset = (row * width + column) * 3;
        picture[offset] = (byte) root;
        picture[offset + 1] = (byte) (root >> 8);
      }
    }

    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), THREADS, Mcv2.Verification.VERIFIED);
    encoder.setFrameBudget(1);
    encoder.setFrameLimit(1);
    final byte[] first = encoder.encode(picture, width, height, 0);
    final int firstLength = first.length;
    assertEquals(76064, firstLength);

    final double expectedLambda = Mcv2Lambdas.raise(72, 16);
    final double lambda = encoder.getStatistics().orElseThrow().getLambda();
    assertEquals(expectedLambda, lambda);

    final int expectedLeafCount = ROOTS_PER_SIDE * ROOTS_PER_SIDE;
    final int leafCount = Mcv2Decoder.parse(first).getLeafCount();
    assertEquals(expectedLeafCount, leafCount);

    final byte[] firstReference = encoder.getReference().orElseThrow();
    assertArrayEquals(picture, firstReference);

    final byte[] next = encoder.encode(picture, width, height, 1);
    final int nextLength = next.length;
    assertEquals(2336, nextLength);

    final long referenceId = Mcv2Decoder.parse(next).getReferenceId();
    assertEquals(0, referenceId);

    final byte[] nextReference = encoder.getReference().orElseThrow();
    assertArrayEquals(picture, nextReference);
  }

  @Test
  void shouldFallBackWhenEveryRetryExceedsTheFormatByteLimit() {
    final int width = 1024;
    final byte[] picture = new byte[width * width * 3];
    new Random(0x131071).nextBytes(picture);
    final byte[] expected = new byte[picture.length];
    for (int top = 0; top < width; top += 32) {
      for (int left = 0; left < width; left += 32) {
        this.averageSquare(picture, expected, width, top, left);
      }
    }

    // Zero lambda cannot reduce the detailed frame by raising its rate cost.
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(0), ForkJoinPool.commonPool(), THREADS, Mcv2.Verification.VERIFIED);
    final byte[] data = encoder.encode(picture, width, width, 0);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    final boolean withinLimit = data.length <= 131071;
    assertTrue(withinLimit);

    final double lambda = encoder.getStatistics().orElseThrow().getLambda();
    assertEquals(0, lambda);

    final int expectedLeafCount = 32 * 32;
    final int leafCount = frame.getLeafCount();
    assertEquals(expectedLeafCount, leafCount);

    for (int index = 0; index < frame.getLeafCount(); index++) {
      final int size = frame.getLeaf(index).size();
      assertEquals(32, size);
    }

    final byte[] reference = encoder.getReference().orElseThrow();
    assertArrayEquals(expected, reference);

    final byte[] decoded = Mcv2Decoder.decode(frame, null, 0);
    assertArrayEquals(expected, decoded);
  }

  private void averageSquare(final byte[] picture, final byte[] expected, final int width, final int top, final int left) {
    for (int channel = 0; channel < 3; channel++) {
      final byte mean = this.squareMean(picture, width, top, left, channel);
      this.fillSquare(expected, width, top, left, channel, mean);
    }
  }

  private byte squareMean(final byte[] picture, final int width, final int top, final int left, final int channel) {
    int sum = 0;
    for (int row = top; row < top + 32; row++) {
      for (int column = left; column < left + 32; column++) {
        sum += picture[(row * width + column) * 3 + channel] & 255;
      }
    }

    return (byte) ((sum + 512) / 1024);
  }

  private void fillSquare(final byte[] expected, final int width, final int top, final int left, final int channel, final byte mean) {
    for (int row = top; row < top + 32; row++) {
      for (int column = left; column < left + 32; column++) {
        expected[(row * width + column) * 3 + channel] = mean;
      }
    }
  }

  @Test
  void shouldRequestARetryFromTheWriterForAnOversizedTree() {
    final Node palette = Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, new byte[14]);
    final Node full = Mcv2Trees.split(Mcv2Trees.split(palette));
    final List<Object> roots = Collections.nCopies(ROOTS_PER_SIDE * ROOTS_PER_SIDE, full.value());
    final @Nullable Object written = Mcv2Internals.call(
      Mcv2.class,
      null,
      "write",
      new Class<?>[] { int.class, int.class, Mcv2FrameKind.class, List.class, long.class, long.class },
      LARGEST_SIDE,
      LARGEST_SIDE,
      Mcv2FrameKind.KEYFRAME,
      roots,
      0L,
      0L
    );
    assertNull(written);
  }
}
