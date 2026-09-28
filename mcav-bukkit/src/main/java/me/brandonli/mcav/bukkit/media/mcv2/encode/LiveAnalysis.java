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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;

import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;

/**
 * The frame-level choice of a live search, which encodes a frame once where the reference encodes it for every
 * candidate global vector and keeps the cheapest. The vector is chosen before the search from what SKIP would cost
 * with it: a block SKIP codes well costs its distortion and one bit, and a block it codes badly costs at least what
 * another leaf would, so each block counts at most {@link #CLIP} times lambda. The vector with the lowest sum wins,
 * the first on a tie. This rewards the area SKIP can take, which is where the reference's cheaper trial comes from.
 */
final class LiveAnalysis {

  /** The most a block counts, in units of lambda: about a local motion leaf with some distortion. */
  private static final double CLIP = 64.0;

  /** The distance between the pixels sampled in each direction: one pixel in sixteen is measured. */
  private static final int STEP = 4;

  private LiveAnalysis() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * What the analysis found.
   *
   * @param vector   the index of the chosen vector
   * @param sceneCut whether the frame changes too much for any prediction: the reference's scene cut test, the mean
   *                 absolute luma change after prediction with the estimated vector above the threshold
   */
  record Result(int vector, boolean sceneCut) {}

  /**
   * Chooses the global vector of a live P frame, and tests for a scene cut on the same predictions.
   *
   * @param source    the picture, row-major RGB
   * @param reference the previous decoded picture
   * @param width     the width
   * @param height    the height
   * @param lambda    the rate-distortion trade
   * @param threshold the scene cut threshold, the mean absolute luma change
   * @param vectors   the candidate vectors, each {@code x << 16 | (y & 0xFFFF)} in half pixels; the last is the estimate
   * @param workers   the workers
   * @return the choice
   */
  static Result analyze(
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final double lambda,
    final double threshold,
    final int[] vectors,
    final Workers workers
  ) {
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int superblocks = columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    final double[][] costs = new double[vectors.length][superblocks];
    final long[] changes = new long[superblocks];
    final int estimate = vectors.length - 1;
    workers.forEach(
      superblocks,
      () -> changes,
      (_, index) -> {
        final int left = (index % columns) * ROOT_SIZE;
        final int top = (index / columns) * ROOT_SIZE;
        for (int vectorIndex = 0; vectorIndex < vectors.length; vectorIndex++) {
          final int motionX = MotionSearch.unpackX(vectors[vectorIndex]);
          final int motionY = MotionSearch.unpackY(vectors[vectorIndex]);
          long distortion = 0;
          long change = 0;
          for (int row = STEP / 2; row < ROOT_SIZE; row += STEP) {
            final int sourceRow = Math.min(top + row, height - 1);
            final int halfPixelY = Math.min(Math.max(2 * (top + row) + motionY, 0), 2 * (height - 1));
            for (int column = STEP / 2; column < ROOT_SIZE; column += STEP) {
              final int sourceColumn = Math.min(left + column, width - 1);
              final int halfPixelX = Math.min(Math.max(2 * (left + column) + motionX, 0), 2 * (width - 1));
              final int at = (sourceRow * width + sourceColumn) * CHANNELS;
              final int red = source[at] & 0xFF;
              final int green = source[at + 1] & 0xFF;
              final int blue = source[at + 2] & 0xFF;
              final int predictedRed = sample(reference, width, height, halfPixelX, halfPixelY, 0);
              final int predictedGreen = sample(reference, width, height, halfPixelX, halfPixelY, 1);
              final int predictedBlue = sample(reference, width, height, halfPixelX, halfPixelY, 2);
              if (vectorIndex == estimate) {
                change += Math.abs(4 * (red + 2 * green + blue) - (predictedRed + 2 * predictedGreen + predictedBlue));
              }
              distortion += Reconstruction.pixelError(
                red - ((predictedRed + 2) >> 2),
                green - ((predictedGreen + 2) >> 2),
                blue - ((predictedBlue + 2) >> 2)
              );
            }
          }
          // every sample stands for STEP * STEP pixels
          costs[vectorIndex][index] = Math.min((distortion * (STEP * STEP)) / Reconstruction.DISTORTION_SCALE + lambda, CLIP * lambda);
          if (vectorIndex == estimate) {
            changes[index] = change * (STEP * STEP);
          }
        }
      }
    );
    long change = 0;
    for (final long value : changes) {
      change += value;
    }
    final double pixels = (double) superblocks * ROOT_SIZE * ROOT_SIZE;
    int best = 0;
    double bestSum = Double.POSITIVE_INFINITY;
    for (int vectorIndex = 0; vectorIndex < vectors.length; vectorIndex++) {
      double sum = 0;
      for (final double cost : costs[vectorIndex]) {
        sum += cost;
      }
      if (sum < bestSum) {
        bestSum = sum;
        best = vectorIndex;
      }
    }
    return new Result(best, change / Mcv2Encoder.SCENE_LUMA_SCALE / pixels > threshold);
  }

  /** Four times the reference's channel at a position in half pixels, sampled as {@link Reconstruction#predict} does. */
  private static int sample(
    final byte[] reference,
    final int width,
    final int height,
    final int halfPixelX,
    final int halfPixelY,
    final int channel
  ) {
    final int leftColumn = halfPixelX >> 1;
    final int topRow = halfPixelY >> 1;
    final int rightColumn = Math.min(leftColumn + 1, width - 1);
    final int bottomRow = Math.min(topRow + 1, height - 1);
    final int topLeftSample = reference[(topRow * width + leftColumn) * CHANNELS + channel] & 0xFF;
    if ((halfPixelX & 1) == 0 && (halfPixelY & 1) == 0) {
      return 4 * topLeftSample;
    }
    if ((halfPixelY & 1) == 0) {
      return 2 * (topLeftSample + (reference[(topRow * width + rightColumn) * CHANNELS + channel] & 0xFF));
    }
    if ((halfPixelX & 1) == 0) {
      return 2 * (topLeftSample + (reference[(bottomRow * width + leftColumn) * CHANNELS + channel] & 0xFF));
    }
    return (
      topLeftSample +
      (reference[(topRow * width + rightColumn) * CHANNELS + channel] & 0xFF) +
      (reference[(bottomRow * width + leftColumn) * CHANNELS + channel] & 0xFF) +
      (reference[(bottomRow * width + rightColumn) * CHANNELS + channel] & 0xFF)
    );
  }
}
