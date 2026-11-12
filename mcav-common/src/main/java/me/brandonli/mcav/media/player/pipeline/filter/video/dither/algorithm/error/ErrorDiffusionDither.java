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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The error diffusion engine shared by every error diffusion algorithm.
 *
 * <p>Error diffusion processes pixels one by one, picks the closest palette color for each, and spreads the
 * difference between the wanted and the chosen color to the neighbors that have not been processed yet, as
 * described by a {@link DiffusionKernel}. Rows are scanned in alternating directions, which avoids the diagonal
 * streaks a fixed scanning direction produces.
 *
 * <p>The engine keeps the pending errors of the next few rows in a small ring of padded rows, so memory use is
 * independent of the image height and no bounds checks are needed at the edges. Subclasses only choose a kernel.
 * The basic kernel implementations hold no state between calls and can be shared. Temporal subclasses retain
 * the history of one stream and must not be shared across streams or threads.
 *
 * <p>This class is thread-safe, except for temporal subclasses.
 *
 * @since 1.0.0
 */
public abstract class ErrorDiffusionDither extends AbstractDitherAlgorithm {

  private final DiffusionKernel kernel;

  /**
   * Creates a new error diffusion algorithm.
   *
   * @param palette the palette to reduce images to
   * @param kernel  the kernel that describes how errors are spread
   * @throws NullPointerException if {@code palette}, {@code kernel} is null
   * @since 1.0.0
   */
  protected ErrorDiffusionDither(final DitherPalette palette, final DiffusionKernel kernel) {
    super(palette);
    Preconditions.checkNotNull(kernel, "Kernel must not be null!");

    this.kernel = kernel;
  }

  /**
   * Dithers an image into palette indices with the kernel of this algorithm. The image is not modified.
   *
   * @param image the image to dither
   * @return the palette index of every pixel, laid out row by row
   * @throws NullPointerException  if {@code image} is null
   * @throws IllegalStateException if the image has been released
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final byte[] indices = new byte[pixels.length];
    this.run(pixels, width, indices);
    return indices;
  }

  /**
   * Dithers pixels in place with the kernel of this algorithm, replacing every pixel with a palette color.
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

    if (buffer.length == 0) {
      return;
    }

    this.run(buffer, width, null);
  }

  private void run(final int[] pixels, final int width, final byte @Nullable [] indices) {
    final DiffusionPass pass = new DiffusionPass(pixels, width, indices);
    pass.run();
  }

  /**
   * Returns the kernel of this algorithm.
   *
   * @return the kernel
   * @since 1.0.0
   */
  public DiffusionKernel getKernel() {
    return this.kernel;
  }

  private final class DiffusionPass {

    private final int[] pixels;
    private final int width;
    private final byte @Nullable [] indices;
    private final byte[] colorMap;
    private final int[] fullColorMap;
    private final ErrorRows errors;

    DiffusionPass(final int[] pixels, final int width, final byte @Nullable [] indices) {
      final DitherPalette palette = ErrorDiffusionDither.this.getPalette();
      this.pixels = pixels;
      this.width = width;
      this.indices = indices;
      this.colorMap = palette.getColorMap();
      this.fullColorMap = palette.getFullColorMap();
      this.errors = new ErrorRows(ErrorDiffusionDither.this.kernel, width);
    }

    void run() {
      final int height = this.pixels.length / this.width;
      for (int row = 0; row < height; row++) {
        this.ditherRow(row);
        this.errors.finishRow(row);
      }
    }

    private void ditherRow(final int row) {
      final int step = ErrorRows.getScanStep(row);
      final int start = ErrorRows.getScanStart(row, this.width);
      final int end = ErrorRows.getScanEnd(row, this.width);
      for (int column = start; column != end; column += step) {
        this.ditherPixel(column, row, step);
      }
    }

    private void ditherPixel(final int column, final int row, final int step) {
      final int pixelIndex = row * this.width + column;
      final int argb = this.pixels[pixelIndex];
      final int wanted = this.errors.applyPendingError(argb, column, row);
      final int lookup = ErrorRows.getLookupIndex(wanted);
      final int chosen = this.fullColorMap[lookup];
      this.store(pixelIndex, lookup, chosen);
      final int errorRed = ErrorRows.red(wanted) - ErrorRows.red(chosen);
      final int errorGreen = ErrorRows.green(wanted) - ErrorRows.green(chosen);
      final int errorBlue = ErrorRows.blue(wanted) - ErrorRows.blue(chosen);
      this.errors.diffuse(column, row, step, errorRed, errorGreen, errorBlue);
    }

    private void store(final int pixelIndex, final int lookup, final int chosen) {
      final byte[] targetIndices = this.indices;
      if (targetIndices == null) {
        this.pixels[pixelIndex] = chosen;
        return;
      }

      targetIndices[pixelIndex] = this.colorMap[lookup];
    }
  }
}
