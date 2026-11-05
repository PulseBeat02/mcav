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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.stream.IntStream;

/**
 * The default {@link DitherPalette} implementation.
 *
 * <p>The lookup tables are built in parallel across all cores when the palette is created. Colors are compared
 * with a perceptual distance that weights the channels by how sensitive the eye is to them, which gives visibly
 * better matches than a plain Euclidean distance.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public class ColorPalette implements DitherPalette {

  private static final int MAXIMUM_COLORS = 256;
  private static final int LEVELS = 128;
  private static final int TABLE_SIZE = LEVELS * LEVELS * LEVELS;
  private static final int RGB_MASK = 0xFFFFFF;
  private static final int OPAQUE = 0xFF << 24;

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int LEVEL_SHIFT = 1;
  private static final int RED_LEVEL_SHIFT = 14;
  private static final int GREEN_LEVEL_SHIFT = 7;

  private static final float MEAN_FACTOR = 0.5f;
  private static final float BASE_WEIGHT = 2.0f;
  private static final float GREEN_WEIGHT = 4.0f;
  private static final float CHANNEL_MAXIMUM = 255.0f;
  private static final float RED_MEAN_SCALE = 256.0f;

  private final int[] palette;
  private final int reservedIndices;
  private final byte[] colorMap;
  private final int[] fullColorMap;

  ColorPalette(final int[] colors, final int reservedIndices) {
    Preconditions.checkNotNull(colors, "Colors must not be null!");
    Preconditions.checkArgument(colors.length <= MAXIMUM_COLORS, "A palette may hold at most %s colors!", MAXIMUM_COLORS);
    Preconditions.checkArgument(reservedIndices >= 0, "Reserved indices must not be negative!");
    Preconditions.checkArgument(colors.length > reservedIndices, "Palette must contain at least one color!");

    this.palette = new int[colors.length];
    for (int index = reservedIndices; index < colors.length; index++) {
      this.palette[index] = OPAQUE | (colors[index] & RGB_MASK);
    }

    this.reservedIndices = reservedIndices;
    this.colorMap = new byte[TABLE_SIZE];
    this.fullColorMap = new int[TABLE_SIZE];
    buildLookupTables(this.palette, reservedIndices, this.colorMap, this.fullColorMap);
  }

  private static void buildLookupTables(final int[] palette, final int reservedIndices, final byte[] colorMap, final int[] fullColorMap) {
    final IntStream reds = IntStream.range(0, LEVELS);
    final IntStream parallelReds = reds.parallel();
    parallelReds.forEach(redLevel -> buildRedSlice(redLevel, palette, reservedIndices, colorMap, fullColorMap));
  }

  private static void buildRedSlice(
    final int redLevel,
    final int[] palette,
    final int reservedIndices,
    final byte[] colorMap,
    final int[] fullColorMap
  ) {
    final int red = redLevel << LEVEL_SHIFT;
    final int sliceStart = redLevel << RED_LEVEL_SHIFT;
    for (int greenLevel = 0; greenLevel < LEVELS; greenLevel++) {
      final int green = greenLevel << LEVEL_SHIFT;
      final int rowStart = sliceStart | (greenLevel << GREEN_LEVEL_SHIFT);
      for (int blueLevel = 0; blueLevel < LEVELS; blueLevel++) {
        final int blue = blueLevel << LEVEL_SHIFT;
        final int index = findClosestIndex(palette, reservedIndices, red, green, blue);
        final int tableIndex = rowStart | blueLevel;
        colorMap[tableIndex] = (byte) index;
        fullColorMap[tableIndex] = palette[index];
      }
    }
  }

  private static int findClosestIndex(final int[] palette, final int reservedIndices, final int red, final int green, final int blue) {
    int closest = reservedIndices;
    float closestDistance = Float.MAX_VALUE;
    for (int index = reservedIndices; index < palette.length; index++) {
      final int color = palette[index];
      final float distance = perceptualDistance(red, green, blue, color);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = index;
      }
    }

    return closest;
  }

  // The squared "redmean" distance, weighting channels by the eye's sensitivity, so it only compares with another distance.
  @VisibleForTesting
  static float perceptualDistance(final int red, final int green, final int blue, final int color) {
    final int otherRed = (color >> RED_SHIFT) & CHANNEL_MASK;
    final int otherGreen = (color >> GREEN_SHIFT) & CHANNEL_MASK;
    final int otherBlue = color & CHANNEL_MASK;
    final float redMean = (red + otherRed) * MEAN_FACTOR;
    final int deltaRed = red - otherRed;
    final int deltaGreen = green - otherGreen;
    final int deltaBlue = blue - otherBlue;
    final float redWeight = BASE_WEIGHT + redMean / RED_MEAN_SCALE;
    final float blueWeight = BASE_WEIGHT + (CHANNEL_MAXIMUM - redMean) / RED_MEAN_SCALE;
    return redWeight * deltaRed * deltaRed + GREEN_WEIGHT * deltaGreen * deltaGreen + blueWeight * deltaBlue * deltaBlue;
  }

  /**
   * Returns the colors of this palette as opaque ARGB integers, indexed by palette index. Reserved transparent indices hold zero.
   *
   * @return the colors, which are shared and must not be modified
   * @since 1.0.0
   */
  @Override
  public int[] getPalette() {
    return this.palette;
  }

  /**
   * Returns the number of colors of this palette, including reserved indices.
   *
   * @return the palette size
   * @since 1.0.0
   */
  @Override
  public int getSize() {
    return this.palette.length;
  }

  /**
   * Returns the number of leading indices of this palette that are reserved for transparency and never returned by the lookup tables.
   *
   * @return the reserved index count
   * @since 1.0.0
   */
  @Override
  public int getReservedIndices() {
    return this.reservedIndices;
  }

  /**
   * Returns the lookup table from 7-bit RGB colors to palette indices, indexed with
   * {@link me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils#getLookupIndex(int, int, int)}.
   *
   * @return the index lookup table, which is shared and must not be modified
   * @since 1.0.0
   */
  @Override
  public byte[] getColorMap() {
    return this.colorMap;
  }

  /**
   * Returns the lookup table from 7-bit RGB colors to the closest palette color as opaque ARGB, indexed like
   * {@link #getColorMap()}.
   *
   * @return the color lookup table, which is shared and must not be modified
   * @since 1.0.0
   */
  @Override
  public int[] getFullColorMap() {
    return this.fullColorMap;
  }
}
