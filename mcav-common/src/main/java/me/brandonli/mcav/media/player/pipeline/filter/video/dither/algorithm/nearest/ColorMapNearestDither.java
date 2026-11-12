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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest;

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
 * Maps every pixel to its closest palette color without dithering. This is the fastest algorithm and produces
 * perfectly stable frames, but shows visible color banding in gradients.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class ColorMapNearestDither extends AbstractDitherAlgorithm implements NearestDither, ParallelDitherAlgorithm {

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int MINIMUM_PIXELS_PER_TASK = 4096;
  private static final int TASKS_PER_THREAD = 2;

  /**
   * Creates a new nearest color algorithm.
   *
   * @param palette the palette to reduce images to
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  public ColorMapNearestDither(final DitherPalette palette) {
    super(palette);
  }

  /**
   * Maps every pixel of an image to the palette index of its closest color. The image is not modified.
   *
   * @param image the image to map
   * @return the palette index of every pixel, laid out row by row
   * @throws NullPointerException  if {@code image} is null
   * @throws IllegalStateException if the image has been released
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int[] pixels = image.getPixels();
    final byte[] indices = new byte[pixels.length];
    this.mapRange(pixels, 0, pixels.length, indices);
    return indices;
  }

  /**
   * Maps every pixel of an image to the palette index of its closest color, splitting the image into ranges that the
   * threads of the pool map, at most one range for every 4096 pixels, so an image of fewer than 8192 pixels is one
   * range. The result is identical to {@link #ditherIntoBytes(ImageBuffer)}.
   *
   * @param image the image to map, which must not be modified while the method runs
   * @param pool  the pool that runs the work
   * @return the palette index of every pixel, laid out row by row
   * @throws NullPointerException                            if the image or pool is null
   * @throws java.util.concurrent.RejectedExecutionException if the pool rejects submitted work
   * @throws IllegalStateException                           if the image has been released
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    checkImage(image);
    Preconditions.checkNotNull(pool, "Pool must not be null!");

    final int[] pixels = image.getPixels();
    final byte[] indices = new byte[pixels.length];
    final int parallelism = pool.getParallelism();
    final int sizeLimitedTasks = pixels.length / MINIMUM_PIXELS_PER_TASK;
    final int taskCount = Math.clamp(sizeLimitedTasks, 1, parallelism * TASKS_PER_THREAD);
    this.mapInParallel(pixels, indices, pool, taskCount);
    return indices;
  }

  private void mapInParallel(final int[] pixels, final byte[] indices, final ForkJoinPool pool, final int taskCount) {
    final int pixelsPerTask = (pixels.length + taskCount - 1) / taskCount;
    final Runnable work = () -> this.mapTasks(pixels, indices, taskCount, pixelsPerTask);
    final ForkJoinTask<?> task = pool.submit(work);
    task.join();
  }

  private void mapTasks(final int[] pixels, final byte[] indices, final int taskCount, final int pixelsPerTask) {
    final IntStream tasks = IntStream.range(0, taskCount);
    final IntStream parallelTasks = tasks.parallel();
    parallelTasks.forEach(taskIndex -> this.mapTask(pixels, indices, taskIndex, pixelsPerTask));
  }

  private void mapTask(final int[] pixels, final byte[] indices, final int taskIndex, final int pixelsPerTask) {
    final int start = taskIndex * pixelsPerTask;
    final int end = Math.min(start + pixelsPerTask, pixels.length);
    this.mapRange(pixels, start, end, indices);
  }

  private void mapRange(final int[] pixels, final int start, final int end, final byte[] indices) {
    final DitherPalette palette = this.getPalette();
    final byte[] colorMap = palette.getColorMap();
    for (int index = start; index < end; index++) {
      final int argb = pixels[index];
      final int red = (argb >> RED_SHIFT) & CHANNEL_MASK;
      final int green = (argb >> GREEN_SHIFT) & CHANNEL_MASK;
      final int blue = argb & CHANNEL_MASK;
      final int lookup = DitherUtils.getLookupIndex(red, green, blue);
      indices[index] = colorMap[lookup];
    }
  }

  /**
   * Replaces every pixel with its closest palette color.
   *
   * @param buffer the ARGB pixels laid out row by row, which are replaced with palette colors
   * @param width  the width of the image, which must divide the length of the buffer
   * @throws NullPointerException     if {@code buffer} is null
   * @throws IllegalArgumentException if width is not positive or does not divide the buffer length
   * @since 1.0.0
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);

    final DitherPalette palette = this.getPalette();
    final int[] fullColorMap = palette.getFullColorMap();
    for (int index = 0; index < buffer.length; index++) {
      final int argb = buffer[index];
      final int red = (argb >> RED_SHIFT) & CHANNEL_MASK;
      final int green = (argb >> GREEN_SHIFT) & CHANNEL_MASK;
      final int blue = argb & CHANNEL_MASK;
      final int lookup = DitherUtils.getLookupIndex(red, green, blue);
      buffer[index] = fullColorMap[lookup];
    }
  }
}
