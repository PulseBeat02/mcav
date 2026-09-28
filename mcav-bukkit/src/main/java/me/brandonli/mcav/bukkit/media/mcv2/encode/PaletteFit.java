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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;

import java.util.Arrays;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;

/**
 * The two-colour palette fit of the reference encoder ({@code encoder.palette_candidate}), reproduced operation for
 * operation: luma extrema seed two endpoints, four Lloyd iterations move them to their clusters' means in float32
 * (means divided in float64 and rounded to float32, as numpy promotes them), the endpoints are rounded to RGB8 and
 * optionally to RGB565, and every pixel finally takes the nearer endpoint, the first one on a tie.
 */
final class PaletteFit {

  /** The reference's Lloyd iterations. */
  private static final int ITERATIONS = 4;

  private PaletteFit() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Fits a palette to a block.
   *
   * @param source         the block's channels, 0..255, {@code count * 3} values
   * @param count          the number of pixels
   * @param shouldQuantize whether the endpoints are rounded to RGB565 before the final assignment
   * @param colors         receives the endpoints: R, G, B of endpoint 0, then of endpoint 1
   * @param selectors      receives one selector per pixel, 0 or 1
   */
  static void fit(final int[] source, final int count, final boolean shouldQuantize, final int[] colors, final byte[] selectors) {
    final float[] endpoints = new float[PALETTE_COLORS * CHANNELS];
    cluster(source, count, endpoints);
    finish(source, count, endpoints, shouldQuantize, colors, selectors);
  }

  /**
   * The Lloyd iterations of the fit, which do not depend on the rounding of the endpoints, so a palette and the
   * patterns of both endpoint precisions share one run.
   *
   * @param source    the block's channels, 0..255, {@code count * 3} values
   * @param count     the number of pixels
   * @param endpoints receives the float32 endpoints: R, G, B of endpoint 0, then of endpoint 1
   */
  static void cluster(final int[] source, final int count, final float[] endpoints) {
    int low = 0;
    int high = 0;
    int lowLuma = Integer.MAX_VALUE;
    int highLuma = Integer.MIN_VALUE;
    for (int pixel = 0; pixel < count; pixel++) {
      final int luma = source[pixel * CHANNELS] + 2 * source[pixel * CHANNELS + 1] + source[pixel * CHANNELS + 2];
      if (luma < lowLuma) {
        lowLuma = luma;
        low = pixel;
      }
      if (luma > highLuma) {
        highLuma = luma;
        high = pixel;
      }
    }
    final float[] centres = endpoints;
    for (int channel = 0; channel < CHANNELS; channel++) {
      centres[channel] = source[low * CHANNELS + channel];
      centres[CHANNELS + channel] = source[high * CHANNELS + channel];
    }
    final long[] sums = new long[PALETTE_COLORS * CHANNELS];
    final int[] weights = new int[PALETTE_COLORS];
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      Arrays.fill(sums, 0);
      weights[0] = 0;
      weights[1] = 0;
      for (int pixel = 0; pixel < count; pixel++) {
        final int red = source[pixel * CHANNELS];
        final int green = source[pixel * CHANNELS + 1];
        final int blue = source[pixel * CHANNELS + 2];
        final int index = nearer(red, green, blue, centres) ? 1 : 0;
        weights[index]++;
        sums[index * CHANNELS] += red;
        sums[index * CHANNELS + 1] += green;
        sums[index * CHANNELS + 2] += blue;
      }
      for (int index = 0; index < PALETTE_COLORS; index++) {
        if (weights[index] > 0) {
          for (int channel = 0; channel < CHANNELS; channel++) {
            centres[index * CHANNELS + channel] = (float) ((double) sums[index * CHANNELS + channel] / weights[index]);
          }
        }
      }
    }
  }

  /**
   * Rounds clustered endpoints to RGB8, optionally to RGB565, and gives every pixel the nearer one.
   *
   * @param source         the block's channels, 0..255, {@code count * 3} values
   * @param count          the number of pixels
   * @param endpoints      the endpoints from {@link #cluster}
   * @param shouldQuantize whether the endpoints are rounded to RGB565 before the final assignment
   * @param colors         receives the endpoints: R, G, B of endpoint 0, then of endpoint 1
   * @param selectors      receives one selector per pixel, 0 or 1
   */
  static void finish(
    final int[] source,
    final int count,
    final float[] endpoints,
    final boolean shouldQuantize,
    final int[] colors,
    final byte[] selectors
  ) {
    round(endpoints, shouldQuantize, colors);
    for (int pixel = 0; pixel < count; pixel++) {
      selectors[pixel] = nearest(source, pixel, colors);
    }
  }

  /**
   * Rounds clustered endpoints to RGB8, and optionally to RGB565.
   *
   * @param endpoints      the endpoints from {@link #cluster}
   * @param shouldQuantize whether to round them to RGB565 as well
   * @param colors         receives the rounded endpoints: R, G, B of endpoint 0, then of endpoint 1
   */
  static void round(final float[] endpoints, final boolean shouldQuantize, final int[] colors) {
    for (int index = 0; index < PALETTE_COLORS * CHANNELS; index++) {
      colors[index] = Reconstruction.rgb8(endpoints[index]);
    }
    if (shouldQuantize) {
      for (int endpoint = 0; endpoint < PALETTE_COLORS; endpoint++) {
        final int at = endpoint * CHANNELS;
        final int value = Mcv2Format.pack565(colors[at], colors[at + 1], colors[at + 2]);
        final int packed = Mcv2Format.unpack565(value & 0xFF, value >> Byte.SIZE);
        colors[at] = (packed >> 16) & 0xFF;
        colors[at + 1] = (packed >> 8) & 0xFF;
        colors[at + 2] = packed & 0xFF;
      }
    }
  }

  /** The selector of a pixel: 1 when endpoint 1 is strictly nearer in integer RGB distance, else 0. */
  private static byte nearest(final int[] source, final int pixel, final int[] colors) {
    final int at = pixel * CHANNELS;
    final int firstRedDelta = source[at] - colors[0];
    final int firstGreenDelta = source[at + 1] - colors[1];
    final int firstBlueDelta = source[at + 2] - colors[2];
    final int secondRedDelta = source[at] - colors[3];
    final int secondGreenDelta = source[at + 1] - colors[4];
    final int secondBlueDelta = source[at + 2] - colors[5];
    final int firstDistance = firstRedDelta * firstRedDelta + firstGreenDelta * firstGreenDelta + firstBlueDelta * firstBlueDelta;
    final int secondDistance = secondRedDelta * secondRedDelta + secondGreenDelta * secondGreenDelta + secondBlueDelta * secondBlueDelta;
    return (byte) (secondDistance < firstDistance ? 1 : 0);
  }

  /**
   * {@link #finish} for a pattern candidate, which is only valid when the selectors repeat along one axis: every row the
   * first row, or every row a single selector. The pixels are assigned row by row, and the assignment stops at the first
   * row after which neither can hold, which is most blocks' first or second row.
   *
   * @param source         the block's channels, 0..255, {@code size * size * 3} values
   * @param size           the block size
   * @param endpoints      the endpoints from {@link #cluster}
   * @param shouldQuantize whether the endpoints are rounded to RGB565 before the assignment
   * @param colors         receives the endpoints: R, G, B of endpoint 0, then of endpoint 1
   * @param selectors      receives one selector per pixel, all of them when the selectors repeat along an axis
   * @return whether they do
   */
  static boolean finishPattern(
    final int[] source,
    final int size,
    final float[] endpoints,
    final boolean shouldQuantize,
    final int[] colors,
    final byte[] selectors
  ) {
    round(endpoints, shouldQuantize, colors);
    boolean columns = true;
    boolean rows = true;
    for (int row = 0; row < size && (columns || rows); row++) {
      for (int column = 0; column < size; column++) {
        final int pixel = row * size + column;
        selectors[pixel] = nearest(source, pixel, colors);
        columns &= selectors[pixel] == selectors[column];
        rows &= selectors[pixel] == selectors[row * size];
      }
    }
    return columns || rows;
  }

  /** Whether endpoint 1 is strictly nearer than endpoint 0, with the reference's float32 squared distances. */
  private static boolean nearer(final int red, final int green, final int blue, final float[] endpoints) {
    final float firstRedDelta = red - endpoints[0];
    final float firstGreenDelta = green - endpoints[1];
    final float firstBlueDelta = blue - endpoints[2];
    final float secondRedDelta = red - endpoints[3];
    final float secondGreenDelta = green - endpoints[4];
    final float secondBlueDelta = blue - endpoints[5];
    final float firstDistance = firstRedDelta * firstRedDelta + firstGreenDelta * firstGreenDelta + firstBlueDelta * firstBlueDelta;
    final float secondDistance = secondRedDelta * secondRedDelta + secondGreenDelta * secondGreenDelta + secondBlueDelta * secondBlueDelta;
    return secondDistance < firstDistance;
  }
}
