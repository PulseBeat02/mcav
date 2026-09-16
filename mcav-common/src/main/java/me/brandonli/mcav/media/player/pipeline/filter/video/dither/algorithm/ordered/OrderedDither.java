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

import com.google.common.base.Preconditions;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Ordered dithering, which adds a tiled threshold pattern to every pixel before picking the closest palette
 * color.
 *
 * <p>Ordered dithering is fully parallel and needs no state, so it is fast and perfectly stable between frames,
 * at the cost of a visible regular pattern. The pattern offsets are scaled to the average distance between
 * neighboring palette colors, so a strength of one spans exactly one color step.
 */
public final class OrderedDither extends AbstractDitherAlgorithm implements BayerDither, ParallelDitherAlgorithm {

  private static final int MIN_ROWS_PER_TASK = 16;

  private final int[][] offsets;
  private final int patternWidth;
  private final int patternHeight;

  /**
   * Constructs a new ordered dithering algorithm.
   *
   * @param palette the palette to reduce images to
   * @param mapper  the threshold pattern
   */
  public OrderedDither(final DitherPalette palette, final PixelMapper mapper) {
    super(palette);
    Preconditions.checkNotNull(mapper, "Pixel mapper must not be null");

    final float[][] matrix = mapper.getMatrix();
    final int size = palette.getSize();
    final int reservedIndices = palette.getReservedIndices();
    final int levels = size - reservedIndices;
    final float spread = computeSpread(levels);
    this.patternHeight = matrix.length;
    this.patternWidth = matrix[0].length;
    this.offsets = new int[this.patternHeight][this.patternWidth];
    for (int y = 0; y < this.patternHeight; y++) {
      for (int x = 0; x < this.patternWidth; x++) {
        this.offsets[y][x] = Math.round(matrix[y][x] * spread);
      }
    }
  }

  // the average step between palette colors per channel, assuming the colors fill the RGB cube evenly
  private static float computeSpread(final int levels) {
    final double colorsPerAxis = Math.cbrt(Math.max(1, levels));
    return (float) (256.0 / colorsPerAxis);
  }

  /**
   * Dithers an image into palette indices by adding the tiled threshold pattern before picking the closest color.
   * The image is not modified.
   *
   * @param image the image to dither
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int height = pixels.length / width;
    final byte[] indices = new byte[pixels.length];
    this.ditherRows(pixels, width, 0, height, indices);
    return indices;
  }

  /**
   * Dithers an image into palette indices, splitting it into bands of at least 16 rows that the threads of the pool
   * dither. The result is identical to {@link #ditherIntoBytes(ImageBuffer)}.
   *
   * @param image the image to dither, which must not be modified while the method runs
   * @param pool  the pool that runs the work
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    checkImage(image);
    Preconditions.checkNotNull(pool, "Pool must not be null");

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int height = pixels.length / width;
    final byte[] indices = new byte[pixels.length];
    final int parallelism = pool.getParallelism();
    final int rowLimitedTasks = height / MIN_ROWS_PER_TASK;
    final int taskCount = Math.clamp(rowLimitedTasks, 1, parallelism * 2);
    this.ditherInParallel(pixels, width, indices, pool, taskCount);
    return indices;
  }

  private void ditherInParallel(final int[] pixels, final int width, final byte[] indices, final ForkJoinPool pool, final int taskCount) {
    final int height = pixels.length / width;
    final int rowsPerTask = (height + taskCount - 1) / taskCount;
    final ForkJoinTask<?> task = pool.submit(() -> {
      final IntStream tasks = IntStream.range(0, taskCount);
      final IntStream parallelTasks = tasks.parallel();
      parallelTasks.forEach(taskIndex -> {
        final int startY = taskIndex * rowsPerTask;
        final int endY = Math.min(startY + rowsPerTask, height);
        this.ditherRows(pixels, width, startY, endY, indices);
      });
    });
    task.join();
  }

  private void ditherRows(final int[] pixels, final int width, final int startY, final int endY, final byte[] indices) {
    final DitherPalette palette = this.getPalette();
    final byte[] colorMap = palette.getColorMap();
    for (int y = startY; y < endY; y++) {
      final int[] patternRow = this.offsets[y % this.patternHeight];
      final int rowStart = y * width;
      for (int x = 0; x < width; x++) {
        final int index = rowStart + x;
        final int offset = patternRow[x % this.patternWidth];
        final int argb = pixels[index];
        final int red = DitherUtils.clamp(((argb >> 16) & 0xFF) + offset);
        final int green = DitherUtils.clamp(((argb >> 8) & 0xFF) + offset);
        final int blue = DitherUtils.clamp((argb & 0xFF) + offset);
        final int lookup = DitherUtils.getLookupIndex(red, green, blue);
        indices[index] = colorMap[lookup];
      }
    }
  }

  /**
   * Dithers pixels in place by adding the tiled threshold pattern and replacing every pixel with the closest palette
   * color.
   *
   * @param buffer the ARGB pixels laid out row by row, which are replaced with palette colors
   * @param width  the width of the image, which must divide the length of the buffer
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);

    final DitherPalette palette = this.getPalette();
    final int[] fullColorMap = palette.getFullColorMap();
    final int height = buffer.length / width;
    for (int y = 0; y < height; y++) {
      final int[] patternRow = this.offsets[y % this.patternHeight];
      final int rowStart = y * width;
      for (int x = 0; x < width; x++) {
        final int index = rowStart + x;
        final int offset = patternRow[x % this.patternWidth];
        final int argb = buffer[index];
        final int red = DitherUtils.clamp(((argb >> 16) & 0xFF) + offset);
        final int green = DitherUtils.clamp(((argb >> 8) & 0xFF) + offset);
        final int blue = DitherUtils.clamp((argb & 0xFF) + offset);
        final int lookup = DitherUtils.getLookupIndex(red, green, blue);
        buffer[index] = fullColorMap[lookup];
      }
    }
  }
}
