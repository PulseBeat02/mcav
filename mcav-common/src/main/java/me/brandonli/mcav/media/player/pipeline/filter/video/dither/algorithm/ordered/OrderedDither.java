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
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
 * at the cost of a visible regular pattern. The pattern offsets are scaled to a channel spacing between
 * palette colors estimated from their count; this is not a measured distance between particular colors.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class OrderedDither extends AbstractDitherAlgorithm implements BayerDither, ParallelDitherAlgorithm {

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int MINIMUM_ROWS_PER_TASK = 16;
  private static final int TASKS_PER_THREAD = 2;

  private static final int MAXIMUM_OFFSET = 255;
  private static final double COLOR_RANGE = 256.0;

  private final int[][] offsets;
  private final int patternWidth;
  private final int patternHeight;

  /**
   * Creates a new ordered dithering algorithm.
   *
   * @param palette the palette to reduce images to
   * @param mapper  the threshold pattern
   * @throws NullPointerException     if {@code palette}, {@code mapper} is null
   * @throws IllegalArgumentException if the matrix of {@code mapper} is empty or its rows differ in length
   * @since 1.0.0
   */
  public OrderedDither(final DitherPalette palette, final PixelMapper mapper) {
    super(palette);
    Preconditions.checkNotNull(mapper, "Pixel mapper must not be null!");

    final float[][] matrix = mapper.getMatrix();
    final boolean rectangular = isRectangularAndNotEmpty(matrix);
    Preconditions.checkArgument(rectangular, "Pixel mapper matrix must be rectangular and not empty!");

    final int size = palette.getSize();
    final int reservedIndices = palette.getReservedIndices();
    final int levels = size - reservedIndices;
    final float spread = computeSpread(levels);
    this.patternHeight = matrix.length;
    this.patternWidth = matrix[0].length;
    this.offsets = new int[this.patternHeight][this.patternWidth];
    for (int row = 0; row < this.patternHeight; row++) {
      for (int column = 0; column < this.patternWidth; column++) {
        this.offsets[row][column] = Math.clamp(Math.round(matrix[row][column] * spread), -MAXIMUM_OFFSET, MAXIMUM_OFFSET);
      }
    }
  }

  private static boolean isRectangularAndNotEmpty(final float[][] matrix) {
    if (matrix.length == 0) {
      return false;
    }

    final int columns = matrix[0].length;
    final Stream<float[]> rows = Arrays.stream(matrix);
    return columns > 0 && rows.allMatch(row -> row.length == columns);
  }

  private static float computeSpread(final int levels) {
    final int usableLevels = Math.max(1, levels);
    final double colorsPerAxis = Math.cbrt(usableLevels);
    return (float) (COLOR_RANGE / colorsPerAxis);
  }

  /**
   * Dithers an image into palette indices by adding the tiled threshold pattern before picking the closest color.
   * The image is not modified.
   *
   * @param image the image to dither
   * @return the palette index of every pixel, laid out row by row
   * @since 1.0.0
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
   * @throws NullPointerException if {@code pool} is null
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    checkImage(image);
    Preconditions.checkNotNull(pool, "Pool must not be null!");

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int height = pixels.length / width;
    final byte[] indices = new byte[pixels.length];
    final int parallelism = pool.getParallelism();
    final int rowLimitedTasks = height / MINIMUM_ROWS_PER_TASK;
    final int taskCount = Math.clamp(rowLimitedTasks, 1, parallelism * TASKS_PER_THREAD);
    this.ditherInParallel(pixels, width, indices, pool, taskCount);
    return indices;
  }

  private void ditherInParallel(final int[] pixels, final int width, final byte[] indices, final ForkJoinPool pool, final int taskCount) {
    final int height = pixels.length / width;
    final int rowsPerTask = (height + taskCount - 1) / taskCount;
    final Runnable work = () -> this.ditherTasks(pixels, width, indices, taskCount, rowsPerTask);
    final ForkJoinTask<?> task = pool.submit(work);
    task.join();
  }

  private void ditherTasks(final int[] pixels, final int width, final byte[] indices, final int taskCount, final int rowsPerTask) {
    final IntStream tasks = IntStream.range(0, taskCount);
    final IntStream parallelTasks = tasks.parallel();
    parallelTasks.forEach(taskIndex -> this.ditherTask(pixels, width, indices, taskIndex, rowsPerTask));
  }

  private void ditherTask(final int[] pixels, final int width, final byte[] indices, final int taskIndex, final int rowsPerTask) {
    final int height = pixels.length / width;
    final int startY = taskIndex * rowsPerTask;
    final int endY = Math.min(startY + rowsPerTask, height);
    this.ditherRows(pixels, width, startY, endY, indices);
  }

  private void ditherRows(final int[] pixels, final int width, final int startY, final int endY, final byte[] indices) {
    final DitherPalette palette = this.getPalette();
    final byte[] colorMap = palette.getColorMap();
    for (int row = startY; row < endY; row++) {
      final int[] patternRow = this.offsets[row % this.patternHeight];
      final int rowStart = row * width;
      for (int column = 0; column < width; column++) {
        final int index = rowStart + column;
        final int offset = patternRow[column % this.patternWidth];
        final int argb = pixels[index];
        final int red = DitherUtils.clamp(((argb >> RED_SHIFT) & CHANNEL_MASK) + offset);
        final int green = DitherUtils.clamp(((argb >> GREEN_SHIFT) & CHANNEL_MASK) + offset);
        final int blue = DitherUtils.clamp((argb & CHANNEL_MASK) + offset);
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
   * @since 1.0.0
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);

    final DitherPalette palette = this.getPalette();
    final int[] fullColorMap = palette.getFullColorMap();
    final int height = buffer.length / width;
    for (int row = 0; row < height; row++) {
      final int[] patternRow = this.offsets[row % this.patternHeight];
      final int rowStart = row * width;
      for (int column = 0; column < width; column++) {
        final int index = rowStart + column;
        final int offset = patternRow[column % this.patternWidth];
        final int argb = buffer[index];
        final int red = DitherUtils.clamp(((argb >> RED_SHIFT) & CHANNEL_MASK) + offset);
        final int green = DitherUtils.clamp(((argb >> GREEN_SHIFT) & CHANNEL_MASK) + offset);
        final int blue = DitherUtils.clamp((argb & CHANNEL_MASK) + offset);
        final int lookup = DitherUtils.getLookupIndex(red, green, blue);
        buffer[index] = fullColorMap[lookup];
      }
    }
  }
}
