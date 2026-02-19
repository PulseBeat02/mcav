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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implementation of Bayer Dithering algorithm using a predefined color palette and a pixel mapping matrix.
 */
public final class OrderedDither extends AbstractDitherAlgorithm implements BayerDither, ParallelDitherAlgorithm {

  private final DitherPalette palette;
  private final float[][] precalc;
  private final int avgLevel;
  private final int xdim;
  private final int ydim;

  /**
   * Constructs an OrderedDither instance with the specified color palette and pixel mapping matrix.
   *
   * @param palette the color palette to be used for dithering. If null, a default palette is used.
   * @param mapper  the pixel mapper
   */
  public OrderedDither(final DitherPalette palette, final PixelMapper mapper) {
    this.palette = palette == null ? DitherPalette.DEFAULT_MAP_PALETTE : palette;
    this.precalc = mapper.getMatrix();
    this.ydim = this.precalc.length;
    this.xdim = this.precalc[0].length;
    this.avgLevel = this.calculateAverageLevel(this.palette);
  }

  private int calculateAverageLevel(final DitherPalette palette) {
    final int[] colors = palette.getPalette();
    final Set<Integer> redValues = new HashSet<>();
    final Set<Integer> greenValues = new HashSet<>();
    final Set<Integer> blueValues = new HashSet<>();
    for (final int color : colors) {
      redValues.add((color >> 16) & 0xFF);
      greenValues.add((color >> 8) & 0xFF);
      blueValues.add(color & 0xFF);
    }
    final int redLevels = redValues.size();
    final int greenLevels = greenValues.size();
    final int blueLevels = blueValues.size();
    return Math.max(2, (redLevels + greenLevels + blueLevels) / 3);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    final int[] buffer = image.getPixels();
    final int width = image.getWidth();
    final int length = buffer.length;
    final int height = length / width;
    final ByteBuffer data = ByteBuffer.allocate(length);
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        final float threshold = this.precalc[y % this.ydim][x % this.xdim];
        r = this.adjustColorBasedOnThreshold(r, threshold);
        g = this.adjustColorBasedOnThreshold(g, threshold);
        b = this.adjustColorBasedOnThreshold(b, threshold);
        data.put(DitherUtils.getBestColor(this.palette, r, g, b));
      }
    }
    return data.array();
  }

  private int adjustColorBasedOnThreshold(final int colorValue, final float threshold) {
    final float step = 255.0f / (this.avgLevel - 1);
    final float normalizedThreshold = (threshold / 255.0f) * step;
    final float adjustedValue = colorValue + normalizedThreshold;
    final int quantizedLevel = Math.round(adjustedValue / step);
    return Math.min(255, Math.max(0, Math.round(quantizedLevel * step)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Each row is independent (no error propagation), so rows are spread across the workers of
   * the supplied {@link ForkJoinPool} via a parallel {@link IntStream}.
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    final int[] buffer = image.getPixels();
    final int width = image.getWidth();
    final int length = buffer.length;
    final int height = length / width;
    final byte[] data = new byte[length];
    pool
      .submit(() ->
        IntStream.range(0, height)
          .parallel()
          .forEach(y -> {
            final int yIndex = y * width;
            for (int x = 0; x < width; x++) {
              final int index = yIndex + x;
              final int color = buffer[index];
              int r = (color >> 16) & 0xFF;
              int g = (color >> 8) & 0xFF;
              int b = color & 0xFF;
              final float threshold = this.precalc[y % this.ydim][x % this.xdim];
              r = this.adjustColorBasedOnThreshold(r, threshold);
              g = this.adjustColorBasedOnThreshold(g, threshold);
              b = this.adjustColorBasedOnThreshold(b, threshold);
              data[index] = DitherUtils.getBestColor(this.palette, r, g, b);
            }
          })
      )
      .join();
    return data;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final int height = buffer.length / width;
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (r += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(r, 0);
        g = (g += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(g, 0);
        b = (b += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(b, 0);
        buffer[index] = DitherUtils.getBestColorNormal(this.palette, r, g, b);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DitherPalette getPalette() {
    return this.palette;
  }
}
