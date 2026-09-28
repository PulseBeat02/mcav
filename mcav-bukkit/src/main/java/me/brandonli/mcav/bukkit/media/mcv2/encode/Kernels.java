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

import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The pixel kernels one block coder reconstructs, measures, fits and searches with: the Java ones ({@link JavaKernels})
 * or a native library's ({@link NativeKernels}), which compute the same values. The decisions stay with the coder; a
 * kernel only does the arithmetic of one block. The scored reconstructions measure the block against the source,
 * rate and limit of the last {@link #start}, and stop at the first row after which the candidate can no longer be
 * cheaper than the limit. An instance belongs to one coder and one thread.
 */
abstract sealed class Kernels permits JavaKernels, NativeKernels {

  /** Makes the kernels of one coder. */
  @FunctionalInterface
  interface Factory {
    /**
     * Makes the kernels of a new coder.
     *
     * @return the kernels
     */
    Kernels create();
  }

  /**
   * Starts measuring a reconstruction.
   *
   * @param source the block's source channels
   * @param rate   the candidate's rate in units of distortion over 96
   * @param limit  the cost the candidate must stay below
   */
  abstract void start(int[] source, double rate, double limit);

  /**
   * Gets the distortion of the last reconstruction a scored kernel finished.
   *
   * @return the distortion, 96 times the weighted YCoCg squared error
   */
  abstract long distortion();

  /** {@link Reconstruction#predicted}, measured; false when the measure stopped it. */
  abstract boolean predicted(int[] prediction, int size, int[] out);

  /** {@link Reconstruction#solid}, measured. */
  abstract boolean solid(int color, int size, int[] out);

  /** {@link Reconstruction#palette}, measured. */
  abstract boolean palette(byte[] record, int offset, int size, int[] out);

  /** {@link Reconstruction#intraGrid}, measured. */
  abstract boolean intraGrid(byte[] record, int offset, int grid, int size, int[] out);

  /** {@link Reconstruction#residualGrid}, measured. */
  abstract boolean residualGrid(int[] prediction, byte[] record, int offset, int grid, int q, int size, int[] out);

  /** {@link Reconstruction#reduced}, measured; an intra record has no prediction. */
  abstract boolean reduced(int @Nullable [] prediction, byte[] record, int offset, int luma, int chroma, int q, int size, int[] out);

  /** {@link Reconstruction#compact}, measured. */
  abstract boolean compact(int[] prediction, byte[] record, int body, int kind, int q, int size, int[] out);

  /** {@link Reconstruction#predict}. */
  abstract void predict(byte[] reference, int width, int height, int x, int y, int size, int mx, int my, int[] out);

  /** {@link Fits#fit}, with the kernels' own scratch space. */
  abstract void fit(float[] values, int offset, int stride, int size, int grid, float[] out, int outOffset, int outStride);

  /** {@link FastFits#cellSums}. */
  abstract void cellSums(int[] source, int size, int[] sums);

  /** {@link FastFits#lumaResidual}, with the kernels' own scratch space. */
  abstract void lumaResidual(int[] source, int[] prediction, int size, float[] nodes);

  /** {@link FastFits#cluster}, with the kernels' own scratch space. */
  abstract void cluster(int[] source, int size, float[] endpoints);

  /** {@link PaletteFit#cluster}. */
  abstract void paletteCluster(int[] source, int count, float[] endpoints);

  /** {@link PaletteFit#finish}. */
  abstract void finish(int[] source, int count, float[] endpoints, boolean quantize, int[] colors, byte[] selectors);

  /** {@link PaletteFit#finishPattern}. */
  abstract boolean finishPattern(int[] source, int size, float[] endpoints, boolean quantize, int[] colors, byte[] selectors);

  /** {@link MotionSearch#seeded}. */
  abstract int seeded(
    byte[] reference,
    int width,
    int height,
    int[] source,
    int x,
    int y,
    int size,
    int globalX,
    int globalY,
    int range,
    boolean halfPixel,
    int[] seeds
  );

  /**
   * Loads a block of a picture into channels, the picture's last row and column repeated past its edges.
   *
   * @param image  the picture, row-major RGB
   * @param width  the picture width
   * @param height the picture height
   * @param x      the block's left edge, inside the picture
   * @param y      the block's top edge, inside the picture
   * @param size   the block size
   * @param source receives {@code size * size * 3} channels
   */
  abstract void loadSource(byte[] image, int width, int height, int x, int y, int size, int[] source);

  /** {@link BlockCoder#halve}. */
  abstract void halve(int[] block, int size, int[] out);

  /**
   * Converts channels to YCoCg: luma always, the chroma only when asked, leaving it untouched otherwise.
   *
   * @param source the channels
   * @param count  the number of pixels
   * @param chroma whether to convert the chroma
   * @param out    receives the YCoCg values, three per pixel
   */
  abstract void ycocg(int[] source, int count, boolean chroma, float[] out);

  /**
   * The YCoCg residual of a YCoCg source against a four-times prediction; the chroma only when asked.
   *
   * @param ycocg      the source in YCoCg
   * @param prediction four times the predicted channels
   * @param count      the number of pixels
   * @param chroma     whether to compute the chroma
   * @param target     receives the residual, three values per pixel
   */
  abstract void residualTarget(float[] ycocg, int[] prediction, int count, boolean chroma, float[] target);

  /**
   * One channel of YCoCg values as the means of a grid's cells, each summed in double.
   *
   * @param target    the values, three per pixel
   * @param size      the block size
   * @param channel   the channel, 0 to 2
   * @param grid      the grid width
   * @param out       receives {@code grid * grid} means
   * @param outOffset the index of mean (0, 0)
   * @param outStride the distance between consecutive means
   */
  abstract void cellMeans(float[] target, int size, int channel, int grid, float[] out, int outOffset, int outStride);

  /**
   * Lets go of the arrays the kernels were passed, once their coder's frame is done: kernels that keep the arrays to
   * reuse what they made of them would otherwise keep a frame alive for as long as the coder waits for the next one.
   */
  void forgetArrays() {
    // Java's kernels keep nothing
  }
}
