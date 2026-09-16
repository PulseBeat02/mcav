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
 * Instances hold no state between calls and are safe to share between threads.
 */
public abstract class ErrorDiffusionDither extends AbstractDitherAlgorithm {

  private final DiffusionKernel kernel;

  /**
   * Constructs a new error diffusion algorithm.
   *
   * @param palette the palette to reduce images to
   * @param kernel  the kernel that describes how errors are spread
   */
  protected ErrorDiffusionDither(final DitherPalette palette, final DiffusionKernel kernel) {
    super(palette);
    Preconditions.checkNotNull(kernel, "Kernel must not be null");
    this.kernel = kernel;
  }

  /**
   * Gets the kernel of this algorithm.
   *
   * @return the kernel
   */
  public DiffusionKernel getKernel() {
    return this.kernel;
  }

  /**
   * Dithers an image into palette indices with the kernel of this algorithm. The image is not modified.
   *
   * @param image the image to dither
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final byte[] indices = new byte[pixels.length];
    this.run(pixels, width, indices, false);
    return indices;
  }

  /**
   * Dithers pixels in place with the kernel of this algorithm, replacing every pixel with a palette color.
   *
   * @param buffer the ARGB pixels laid out row by row, which are replaced with palette colors
   * @param width  the width of the image, which must divide the length of the buffer
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);
    this.run(buffer, width, null, true);
  }

  /**
   * Runs the algorithm over an image.
   *
   * @param pixels  the ARGB pixels of the image
   * @param width   the width of the image
   * @param indices the array that receives the palette indices, or null if none are wanted
   * @param inPlace true to replace the pixels with palette colors
   */
  private void run(final int[] pixels, final int width, final byte@Nullable[] indices, final boolean inPlace) {
    final DiffusionPass pass = new DiffusionPass(pixels, width, indices, inPlace);
    pass.run();
  }

  /**
   * One pass of the algorithm over one image.
   */
  private final class DiffusionPass {

    private final int[] pixels;
    private final int width;
    private final byte@Nullable[] indices;
    private final boolean inPlace;
    private final byte[] colorMap;
    private final int[] fullColorMap;
    private final ErrorRows errors;

    DiffusionPass(final int[] pixels, final int width, final byte@Nullable[] indices, final boolean inPlace) {
      final DitherPalette palette = ErrorDiffusionDither.this.getPalette();
      this.pixels = pixels;
      this.width = width;
      this.indices = indices;
      this.inPlace = inPlace;
      this.colorMap = palette.getColorMap();
      this.fullColorMap = palette.getFullColorMap();
      this.errors = new ErrorRows(ErrorDiffusionDither.this.kernel, width);
    }

    void run() {
      final int height = this.pixels.length / this.width;
      for (int y = 0; y < height; y++) {
        this.ditherRow(y);
        this.errors.finishRow(y);
      }
    }

    private void ditherRow(final int y) {
      final int step = ErrorRows.getScanStep(y);
      final int start = ErrorRows.getScanStart(y, this.width);
      final int end = ErrorRows.getScanEnd(y, this.width);
      for (int x = start; x != end; x += step) {
        this.ditherPixel(x, y, step);
      }
    }

    private void ditherPixel(final int x, final int y, final int step) {
      final int pixelIndex = y * this.width + x;
      final int argb = this.pixels[pixelIndex];
      final int wanted = this.errors.applyPendingError(argb, x, y);
      final int lookup = ErrorRows.getLookupIndex(wanted);
      final int chosen = this.fullColorMap[lookup];
      this.store(pixelIndex, lookup, chosen);
      final int errorRed = ErrorRows.red(wanted) - ErrorRows.red(chosen);
      final int errorGreen = ErrorRows.green(wanted) - ErrorRows.green(chosen);
      final int errorBlue = ErrorRows.blue(wanted) - ErrorRows.blue(chosen);
      this.errors.diffuse(x, y, step, errorRed, errorGreen, errorBlue);
    }

    private void store(final int pixelIndex, final int lookup, final int chosen) {
      final byte[] targetIndices = this.indices;
      if (targetIndices != null) {
        targetIndices[pixelIndex] = this.colorMap[lookup];
      }
      if (this.inPlace) {
        this.pixels[pixelIndex] = chosen;
      }
    }
  }
}
