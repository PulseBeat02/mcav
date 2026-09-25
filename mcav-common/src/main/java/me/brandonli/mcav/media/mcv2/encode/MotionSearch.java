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
package me.brandonli.mcav.media.mcv2.encode;

/**
 * The local motion search of the reference encoder ({@code encoder.local_motion}): a hierarchical eight-neighbour
 * diamond around the frame-global vector on sixteen stratified samples of the block, refined to half pixels.
 *
 * <p>The reference scores a candidate by the mean absolute difference in float64; every predicted sample is a multiple
 * of a quarter, so that mean is an exact rational and comparing it is comparing an integer sum of four times the
 * differences. This search compares those integers, so it makes exactly the reference's decisions, ties included: a
 * candidate replaces the best only when it is strictly better, and all eight neighbours of a step are measured around
 * the vector the step started from.
 */
final class MotionSearch {

  private static final int[][] DIRECTIONS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, { -1, -1 }, { 1, 1 }, { -1, 1 }, { 1, -1 } };

  private MotionSearch() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * The search steps in half pixels: powers of two from the range down to one pixel, then a half pixel.
   *
   * @param range     the search range in pixels
   * @param halfPixel whether to refine to half pixels
   * @return the steps
   */
  static int[] steps(final int range, final boolean halfPixel) {
    if (range == 0) {
      return new int[0];
    }
    int step = Integer.highestOneBit(Math.max(1, range));
    final int count = Integer.numberOfTrailingZeros(step) + 1 + (halfPixel ? 1 : 0);
    final int[] steps = new int[count];
    int i = 0;
    while (step > 0) {
      steps[i++] = step * 2;
      step /= 2;
    }
    if (halfPixel) {
      steps[i] = 1;
    }
    return steps;
  }

  /**
   * Searches one block.
   *
   * @param reference the reference picture, row-major RGB
   * @param width     the picture width
   * @param height    the picture height
   * @param source    the block's source channels, edge-padded, {@code size * size * 3} values
   * @param x         the block's left edge
   * @param y         the block's top edge
   * @param size      the block size
   * @param globalX   the global horizontal vector in half pixels
   * @param globalY   the global vertical vector in half pixels
   * @param range     the search range in pixels
   * @param steps     the search steps from {@link #steps(int, boolean)}
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  static int search(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int x,
    final int y,
    final int size,
    final int globalX,
    final int globalY,
    final int range,
    final int[] steps
  ) {
    final int[] samples = new int[4];
    for (int k = 0; k < 4; k++) {
      samples[k] = Math.min((k * size) / 4 + size / 8, size - 1);
    }
    int vx = globalX;
    int vy = globalY;
    long best = cost(reference, width, height, source, x, y, size, samples, vx, vy);
    final int lowX = globalX - range * 2;
    final int highX = globalX + range * 2;
    final int lowY = globalY - range * 2;
    final int highY = globalY + range * 2;
    for (final int step : steps) {
      final int cx = vx;
      final int cy = vy;
      for (final int[] direction : DIRECTIONS) {
        final int hx = Math.min(Math.max(cx + direction[0] * step, lowX), highX);
        final int hy = Math.min(Math.max(cy + direction[1] * step, lowY), highY);
        final long error = cost(reference, width, height, source, x, y, size, samples, hx, hy);
        if (error < best) {
          best = error;
          vx = hx;
          vy = hy;
        }
      }
    }
    return (vx << 16) | (vy & 0xFFFF);
  }

  /** Four times the sum of absolute differences on the sixteen samples, all three channels. */
  private static long cost(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int x,
    final int y,
    final int size,
    final int[] samples,
    final int mx,
    final int my
  ) {
    long sum = 0;
    for (int j = 0; j < 4; j++) {
      final int py = y + samples[j];
      final int hy = Math.min(Math.max(2 * py + my, 0), 2 * (height - 1));
      final int y0 = hy >> 1;
      final int y1 = Math.min(y0 + 1, height - 1);
      final boolean halfY = (hy & 1) != 0;
      for (int i = 0; i < 4; i++) {
        final int px = x + samples[i];
        final int hx = Math.min(Math.max(2 * px + mx, 0), 2 * (width - 1));
        final int x0 = hx >> 1;
        final int x1 = Math.min(x0 + 1, width - 1);
        final boolean halfX = (hx & 1) != 0;
        final int target = (samples[j] * size + samples[i]) * 3;
        for (int c = 0; c < 3; c++) {
          final int a = reference[(y0 * width + x0) * 3 + c] & 0xFF;
          final int value4;
          if (!halfX && !halfY) {
            value4 = 4 * a;
          } else if (!halfY) {
            value4 = 2 * (a + (reference[(y0 * width + x1) * 3 + c] & 0xFF));
          } else if (!halfX) {
            value4 = 2 * (a + (reference[(y1 * width + x0) * 3 + c] & 0xFF));
          } else {
            value4 = a +
            (reference[(y0 * width + x1) * 3 + c] & 0xFF) +
            (reference[(y1 * width + x0) * 3 + c] & 0xFF) +
            (reference[(y1 * width + x1) * 3 + c] & 0xFF);
          }
          sum += Math.abs(value4 - 4 * source[target + c]);
        }
      }
    }
    return sum;
  }
}
