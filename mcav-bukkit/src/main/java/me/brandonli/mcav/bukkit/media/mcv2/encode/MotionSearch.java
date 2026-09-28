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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.sizeIndex;

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

  /** The sampled rows of a block, and as many columns. */
  private static final int SAMPLED = 4;

  /** The first directions: the four whole-pixel neighbours along the axes. */
  private static final int AXES = 4;

  /** The four sampled rows and columns of blocks of 8, 16 and 32 pixels: {@code min(sampleIndex size / 4 + size / 8, size - 1)}. */
  private static final int[][] SAMPLES = new int[BLOCK_SIZES][SAMPLED];

  static {
    for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
      final int size = SMALLEST_BLOCK << sizeIndex;
      for (int sampleIndex = 0; sampleIndex < SAMPLED; sampleIndex++) {
        SAMPLES[sizeIndex][sampleIndex] = Math.min((sampleIndex * size) / SAMPLED + size / (2 * SAMPLED), size - 1);
      }
    }
  }

  private MotionSearch() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Packs a vector into one int, {@code x << 16 | (y & 0xFFFF)}.
   *
   * @param vectorX the horizontal part, in half pixels
   * @param vectorY the vertical part, in half pixels
   * @return the packed vector
   */
  static int pack(final int vectorX, final int vectorY) {
    return (vectorX << Short.SIZE) | (vectorY & 0xFFFF);
  }

  /**
   * The horizontal part of a packed vector.
   *
   * @param vector the vector from {@link #pack}
   * @return the horizontal part, in half pixels
   */
  static int unpackX(final int vector) {
    return vector >> Short.SIZE;
  }

  /**
   * The vertical part of a packed vector.
   *
   * @param vector the vector from {@link #pack}
   * @return the vertical part, in half pixels
   */
  static int unpackY(final int vector) {
    return (short) vector;
  }

  /**
   * The search steps in half pixels: powers of two from the range down to one pixel, then a half pixel.
   *
   * @param range             the search range in pixels
   * @param refinesHalfPixels whether to refine to half pixels
   * @return the steps
   */
  static int[] steps(final int range, final boolean refinesHalfPixels) {
    if (range == 0) {
      return new int[0];
    }
    int step = Integer.highestOneBit(Math.max(1, range));
    final int count = Integer.numberOfTrailingZeros(step) + 1 + (refinesHalfPixels ? 1 : 0);
    final int[] steps = new int[count];
    int next = 0;
    while (step > 0) {
      steps[next++] = step * 2;
      step /= 2;
    }
    if (refinesHalfPixels) {
      steps[next] = 1;
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
   * @param blockLeft the block's left edge
   * @param blockTop  the block's top edge
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
    final int blockLeft,
    final int blockTop,
    final int size,
    final int globalX,
    final int globalY,
    final int range,
    final int[] steps
  ) {
    final int[] samples = SAMPLES[sizeIndex(size)];
    int bestX = globalX;
    int bestY = globalY;
    long best = cost(reference, width, height, source, blockLeft, blockTop, size, samples, bestX, bestY);
    final int lowX = globalX - range * 2;
    final int highX = globalX + range * 2;
    final int lowY = globalY - range * 2;
    final int highY = globalY + range * 2;
    for (final int step : steps) {
      final int centreX = bestX;
      final int centreY = bestY;
      for (final int[] direction : DIRECTIONS) {
        final int candidateX = Math.min(Math.max(centreX + direction[0] * step, lowX), highX);
        final int candidateY = Math.min(Math.max(centreY + direction[1] * step, lowY), highY);
        final long error = cost(reference, width, height, source, blockLeft, blockTop, size, samples, candidateX, candidateY);
        if (error < best) {
          best = error;
          bestX = candidateX;
          bestY = candidateY;
        }
      }
    }
    return pack(bestX, bestY);
  }

  /**
   * Searches one block from seeds, the live search: the best of the seed vectors, then steps of one pixel to the best of
   * the four neighbours for as long as one is better, then, with half pixels, the best of the eight half-pixel
   * neighbours. Every vector stays within the range around the global vector, and a vector replaces the best only when
   * it is strictly better, so the result does not depend on the order of equal candidates after the first.
   *
   * @param reference         the reference picture, row-major RGB
   * @param width             the picture width
   * @param height            the picture height
   * @param source            the block's source channels, edge-padded, {@code size * size * 3} values
   * @param blockLeft         the block's left edge
   * @param blockTop          the block's top edge
   * @param size              the block size
   * @param globalX           the global horizontal vector in half pixels
   * @param globalY           the global vertical vector in half pixels
   * @param range             the search range in pixels
   * @param refinesHalfPixels whether to refine to half pixels
   * @param seeds             the seed vectors as {@code x << 16 | (y & 0xFFFF)}; the global vector is always tried first
   * @return the vector as {@code x << 16 | (y & 0xFFFF)}, in half pixels
   */
  static int seeded(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int globalX,
    final int globalY,
    final int range,
    final boolean refinesHalfPixels,
    final int[] seeds
  ) {
    final int[] samples = SAMPLES[sizeIndex(size)];
    final int lowX = globalX - range * 2;
    final int highX = globalX + range * 2;
    final int lowY = globalY - range * 2;
    final int highY = globalY + range * 2;
    int bestX = globalX;
    int bestY = globalY;
    long best = cost(reference, width, height, source, blockLeft, blockTop, size, samples, bestX, bestY);
    for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
      final int seedX = Math.min(Math.max(unpackX(seeds[seedIndex]), lowX), highX);
      final int seedY = Math.min(Math.max(unpackY(seeds[seedIndex]), lowY), highY);
      // a vector already measured, the global one or an earlier seed, cannot be strictly better than itself
      boolean seen = seedX == globalX && seedY == globalY;
      for (int earlierIndex = 0; earlierIndex < seedIndex && !seen; earlierIndex++) {
        seen =
          seedX == Math.min(Math.max(unpackX(seeds[earlierIndex]), lowX), highX) &&
          seedY == Math.min(Math.max(unpackY(seeds[earlierIndex]), lowY), highY);
      }
      if (!seen) {
        final long error = cost(reference, width, height, source, blockLeft, blockTop, size, samples, seedX, seedY);
        if (error < best) {
          best = error;
          bestX = seedX;
          bestY = seedY;
        }
      }
    }
    // whole pixels: at most one step per pixel of range, so the walk ends even on a pathological surface
    for (int steps = 0; steps < 2 * range; steps++) {
      final int centreX = bestX;
      final int centreY = bestY;
      for (int directionIndex = 0; directionIndex < AXES; directionIndex++) {
        final int candidateX = Math.min(Math.max(centreX + DIRECTIONS[directionIndex][0] * 2, lowX), highX);
        final int candidateY = Math.min(Math.max(centreY + DIRECTIONS[directionIndex][1] * 2, lowY), highY);
        final long error = cost(reference, width, height, source, blockLeft, blockTop, size, samples, candidateX, candidateY);
        if (error < best) {
          best = error;
          bestX = candidateX;
          bestY = candidateY;
        }
      }
      if (bestX == centreX && bestY == centreY) {
        break;
      }
    }
    if (refinesHalfPixels) {
      final int centreX = bestX;
      final int centreY = bestY;
      for (final int[] direction : DIRECTIONS) {
        final int candidateX = Math.min(Math.max(centreX + direction[0], lowX), highX);
        final int candidateY = Math.min(Math.max(centreY + direction[1], lowY), highY);
        final long error = cost(reference, width, height, source, blockLeft, blockTop, size, samples, candidateX, candidateY);
        if (error < best) {
          best = error;
          bestX = candidateX;
          bestY = candidateY;
        }
      }
    }
    return pack(bestX, bestY);
  }

  /** Four times the sum of absolute differences on the sixteen samples, all three channels. */
  private static long cost(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int[] samples,
    final int motionX,
    final int motionY
  ) {
    // every sample and the neighbours a half pixel averages inside the picture: no clamping, one sampling case
    final int left = blockLeft + samples[0] + (motionX >> 1);
    final int right = blockLeft + samples[SAMPLED - 1] + (motionX >> 1) + (motionX & 1);
    final int top = blockTop + samples[0] + (motionY >> 1);
    final int bottom = blockTop + samples[SAMPLED - 1] + (motionY >> 1) + (motionY & 1);
    if (left >= 0 && top >= 0 && right < width && bottom < height) {
      return inside(reference, width, source, blockLeft, blockTop, size, samples, motionX, motionY);
    }
    long sum = 0;
    for (int sampleRow = 0; sampleRow < SAMPLED; sampleRow++) {
      final int pixelRow = blockTop + samples[sampleRow];
      final int halfPixelY = Math.min(Math.max(2 * pixelRow + motionY, 0), 2 * (height - 1));
      final int topRow = halfPixelY >> 1;
      final int bottomRow = Math.min(topRow + 1, height - 1);
      final boolean halfY = (halfPixelY & 1) != 0;
      for (int sampleColumn = 0; sampleColumn < SAMPLED; sampleColumn++) {
        final int pixelColumn = blockLeft + samples[sampleColumn];
        final int halfPixelX = Math.min(Math.max(2 * pixelColumn + motionX, 0), 2 * (width - 1));
        final int leftColumn = halfPixelX >> 1;
        final int rightColumn = Math.min(leftColumn + 1, width - 1);
        final boolean halfX = (halfPixelX & 1) != 0;
        final int target = (samples[sampleRow] * size + samples[sampleColumn]) * CHANNELS;
        for (int channel = 0; channel < CHANNELS; channel++) {
          final int topLeftSample = reference[(topRow * width + leftColumn) * CHANNELS + channel] & 0xFF;
          final int value4;
          if (!halfX && !halfY) {
            value4 = 4 * topLeftSample;
          } else if (!halfY) {
            value4 = 2 * (topLeftSample + (reference[(topRow * width + rightColumn) * CHANNELS + channel] & 0xFF));
          } else if (!halfX) {
            value4 = 2 * (topLeftSample + (reference[(bottomRow * width + leftColumn) * CHANNELS + channel] & 0xFF));
          } else {
            value4 =
              topLeftSample +
              (reference[(topRow * width + rightColumn) * CHANNELS + channel] & 0xFF) +
              (reference[(bottomRow * width + leftColumn) * CHANNELS + channel] & 0xFF) +
              (reference[(bottomRow * width + rightColumn) * CHANNELS + channel] & 0xFF);
          }
          sum += Math.abs(value4 - 4 * source[target + channel]);
        }
      }
    }
    return sum;
  }

  /** {@link #cost} of samples whose displaced positions, and their half-pixel neighbours, lie inside the picture. */
  private static long inside(
    final byte[] reference,
    final int width,
    final int[] source,
    final int blockLeft,
    final int blockTop,
    final int size,
    final int[] samples,
    final int motionX,
    final int motionY
  ) {
    final int right = (motionX & 1) * CHANNELS;
    final int below = (motionY & 1) * width * CHANNELS;
    final int kind = (motionX & 1) | ((motionY & 1) << 1);
    long sum = 0;
    for (int sampleRow = 0; sampleRow < SAMPLED; sampleRow++) {
      final int row = (blockTop + samples[sampleRow] + (motionY >> 1)) * width + blockLeft + (motionX >> 1);
      final int line = samples[sampleRow] * size;
      for (int sampleColumn = 0; sampleColumn < SAMPLED; sampleColumn++) {
        final int topLeft = (row + samples[sampleColumn]) * CHANNELS;
        final int target = (line + samples[sampleColumn]) * CHANNELS;
        switch (kind) {
          case 0 -> {
            sum += Math.abs(4 * (reference[topLeft] & 0xFF) - 4 * source[target]);
            sum += Math.abs(4 * (reference[topLeft + 1] & 0xFF) - 4 * source[target + 1]);
            sum += Math.abs(4 * (reference[topLeft + 2] & 0xFF) - 4 * source[target + 2]);
          }
          case 1, 2 -> {
            final int neighbour = topLeft + right + below;
            sum += Math.abs(2 * ((reference[topLeft] & 0xFF) + (reference[neighbour] & 0xFF)) - 4 * source[target]);
            sum += Math.abs(2 * ((reference[topLeft + 1] & 0xFF) + (reference[neighbour + 1] & 0xFF)) - 4 * source[target + 1]);
            sum += Math.abs(2 * ((reference[topLeft + 2] & 0xFF) + (reference[neighbour + 2] & 0xFF)) - 4 * source[target + 2]);
          }
          default -> {
            final int topRight = topLeft + right;
            final int bottomLeft = topLeft + below;
            final int bottomRight = bottomLeft + right;
            for (int channel = 0; channel < CHANNELS; channel++) {
              final int value4 =
                (reference[topLeft + channel] & 0xFF) +
                (reference[topRight + channel] & 0xFF) +
                (reference[bottomLeft + channel] & 0xFF) +
                (reference[bottomRight + channel] & 0xFF);
              sum += Math.abs(value4 - 4 * source[target + channel]);
            }
          }
        }
      }
    }
    return sum;
  }
}
