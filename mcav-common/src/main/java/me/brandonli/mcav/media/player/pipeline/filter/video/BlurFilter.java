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
   */
  public BlurFilter(final BlurType type, final int kernelSize, final double sigmaX, final double sigmaY) {
    Preconditions.checkNotNull(type, "Blur type must not be null");
    Preconditions.checkArgument(kernelSize > 0, "Kernel size must be positive but was %s", kernelSize);
    final boolean odd = kernelSize % 2 == 1;
    Preconditions.checkArgument(odd || type == BlurType.NORMAL, "Kernel size of a %s blur must be odd but was %s", type, kernelSize);
    Preconditions.checkArgument(sigmaX >= 0 && sigmaY >= 0, "Sigma values must not be negative");
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
   */
  public BlurFilter(final BlurType type, final int kernelSize) {
    this(type, kernelSize, 0, 0);
  }

  /**
   * Blurs the frame in place with the algorithm of this filter.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    return switch (this.type) {
      case NORMAL -> this.normalBlur(mat);
      case MEDIAN -> this.medianBlur(mat);
      case GAUSSIAN -> this.gaussianBlur(mat);
      case STACK -> this.stackBlur(mat);
    };
  }

  private boolean normalBlur(final Mat mat) {
    opencv_imgproc.blur(mat, mat, this.size);
    return true;
  }

  private boolean medianBlur(final Mat mat) {
    opencv_imgproc.medianBlur(mat, mat, this.kernelSize);
    return true;
  }

  private boolean gaussianBlur(final Mat mat) {
    opencv_imgproc.GaussianBlur(mat, mat, this.size, this.sigmaX, this.sigmaY, opencv_core.BORDER_DEFAULT, opencv_core.ALGO_HINT_DEFAULT);
    return true;
  }

  private boolean stackBlur(final Mat mat) {
    opencv_imgproc.stackBlur(mat, mat, this.size);
    return true;
  }

  /**
   * The blur algorithms supported by {@link BlurFilter}.
   */
  public enum BlurType {
    /**
     * Averages the pixels of the kernel. The fastest algorithm.
     */
    NORMAL,

    /**
     * Uses the median of the kernel, which removes noise while keeping edges.
     */
    MEDIAN,

    /**
     * Weights pixels by a Gaussian curve, which gives the most natural blur.
     */
    GAUSSIAN,

    /**
     * Approximates a Gaussian blur at a fraction of the cost for large kernels.
     */
    STACK,
  }
}
