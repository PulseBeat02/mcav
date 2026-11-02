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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import com.google.common.base.Preconditions;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * Blurs frames with one of several blur algorithms; larger kernels blur more. The box blur of
 * {@link BlurType#NORMAL} accepts any positive kernel size, while the median, Gaussian, and stack blurs need odd
 * sizes, as OpenCV does.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public class BlurFilter extends MatVideoFilter {

  private final BlurType type;
  private final Size size;
  private final int kernelSize;
  private final double sigmaX;
  private final double sigmaY;

  /**
   * Constructs a new blur filter.
   *
   * @param type       the blur algorithm
   * @param kernelSize the size of the blur kernel, which must be positive, and odd for every type but
   *                   {@link BlurType#NORMAL}
   * @param sigmaX     the horizontal standard deviation, only used by {@link BlurType#GAUSSIAN}; 0 derives it
   *                   from the kernel size
   * @param sigmaY     the vertical standard deviation, only used by {@link BlurType#GAUSSIAN}; 0 uses the
   *                   horizontal value
   * @throws IllegalArgumentException if the kernel is nonpositive, even for a non-normal blur,
   *                                  or a sigma is negative or NaN
   * @throws NullPointerException     if {@code type} is null
   * @since 1.0.0
   */
  public BlurFilter(final BlurType type, final int kernelSize, final double sigmaX, final double sigmaY) {
    Preconditions.checkNotNull(type, "Blur type must not be null!");
    Preconditions.checkArgument(kernelSize > 0, "Kernel size %s must be positive!", kernelSize);

    final boolean odd = kernelSize % 2 == 1;
    Preconditions.checkArgument(odd || type == BlurType.NORMAL, "Kernel size %s of a %s blur must be odd!", kernelSize, type);
    Preconditions.checkArgument(sigmaX >= 0 && sigmaY >= 0, "Sigma values must not be negative!");

    this.type = type;
    this.size = new Size(kernelSize, kernelSize);
    this.kernelSize = kernelSize;
    this.sigmaX = sigmaX;
    this.sigmaY = sigmaY;
  }

  /**
   * Constructs a new blur filter with derived Gaussian parameters.
   *
   * @param type       the blur algorithm
   * @param kernelSize the size of the blur kernel, which must be positive, and odd for every type but
   *                   {@link BlurType#NORMAL}
   * @throws NullPointerException     if {@code type} is null
   * @throws IllegalArgumentException if the kernel is nonpositive or even for a non-normal blur
   * @since 1.0.0
   */
  public BlurFilter(final BlurType type, final int kernelSize) {
    this(type, kernelSize, 0, 0);
  }

  /**
   * Blurs the frame in place with the algorithm of this filter.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    return switch (this.type) {
      case NORMAL -> this.normalBlur(matrix);
      case MEDIAN -> this.medianBlur(matrix);
      case GAUSSIAN -> this.gaussianBlur(matrix);
      case STACK -> this.stackBlur(matrix);
    };
  }

  private boolean normalBlur(final Mat matrix) {
    opencv_imgproc.blur(matrix, matrix, this.size);

    return true;
  }

  private boolean medianBlur(final Mat matrix) {
    opencv_imgproc.medianBlur(matrix, matrix, this.kernelSize);

    return true;
  }

  private boolean gaussianBlur(final Mat matrix) {
    opencv_imgproc.GaussianBlur(
      matrix,
      matrix,
      this.size,
      this.sigmaX,
      this.sigmaY,
      opencv_core.BORDER_DEFAULT,
      opencv_core.ALGO_HINT_DEFAULT
    );

    return true;
  }

  private boolean stackBlur(final Mat matrix) {
    opencv_imgproc.stackBlur(matrix, matrix, this.size);

    return true;
  }

  /**
   * The blur algorithms supported by {@link BlurFilter}.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public enum BlurType {
    /**
     * Averages the pixels of the kernel. The fastest algorithm.
     *
     * @since 1.0.0
     */
    NORMAL,

    /**
     * Uses the median of the kernel, which removes noise while keeping edges.
     *
     * @since 1.0.0
     */
    MEDIAN,

    /**
     * Weights pixels by a Gaussian curve, which gives the most natural blur.
     *
     * @since 1.0.0
     */
    GAUSSIAN,

    /**
     * Approximates a Gaussian blur at a fraction of the cost for large kernels.
     *
     * @since 1.0.0
     */
    STACK,
  }
}
