/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.function.Consumer;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class BlurFilter extends MatVideoFilter {

  private final Size size;
  private final int kernelSize;
  private final double sigmaX;
  private final double sigmaY;

  private final Consumer<Mat> blurFunction;

  @SuppressWarnings("all") // checker
  public BlurFilter(final BlurType type, final int kernelSize, final double sigmaX, final double sigmaY) {
    this.size = new Size(kernelSize, kernelSize);
    this.kernelSize = kernelSize;
    this.sigmaX = sigmaX;
    this.sigmaY = sigmaY;
    switch (type) {
      case NORMAL -> this.blurFunction = this::applyNormalBlur;
      case MEDIAN -> this.blurFunction = this::applyMedianBlur;
      case GAUSSIAN -> this.blurFunction = this::applyGaussianBlur;
      case STACK -> this.blurFunction = this::applyStackBlur;
      default -> throw new IllegalArgumentException("Unsupported blur type: " + type);
    }
  }

  private void applyNormalBlur(final Mat mat) {
    Imgproc.blur(mat, mat, this.size);
  }

  private void applyMedianBlur(final Mat mat) {
    Imgproc.medianBlur(mat, mat, this.kernelSize);
  }

  private void applyGaussianBlur(final Mat mat) {
    Imgproc.GaussianBlur(mat, mat, this.size, this.sigmaX, this.sigmaY);
  }

  private void applyStackBlur(final Mat mat) {
    Imgproc.stackBlur(mat, mat, this.size);
  }

  @Override
  void modifyMat(final Mat mat) {
    this.blurFunction.accept(mat);
  }

  public enum BlurType {
    NORMAL,
    MEDIAN,
    GAUSSIAN,
    STACK,
  }
}
