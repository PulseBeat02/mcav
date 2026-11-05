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
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Adjusts the contrast and brightness of frames. Every channel value is multiplied by the contrast factor and
 * the brightness offset is added, with the result clamped to the valid range.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class LuminanceFilter extends MatVideoFilter {

  private static final double MAXIMUM_BRIGHTNESS = 255.0;

  private final double contrast;
  private final double brightness;

  /**
   * Constructs a new luminance filter.
   *
   * @param contrast   the factor every channel is multiplied by; 1 keeps the contrast, values above 1 increase it
   * @param brightness the offset added to every channel, from -255 to 255; 0 keeps the brightness
   * @throws IllegalArgumentException if contrast is negative or NaN, or brightness is outside -255 through 255 or NaN
   * @since 1.0.0
   */
  public LuminanceFilter(final double contrast, final double brightness) {
    Preconditions.checkArgument(contrast >= 0, "Contrast must not be negative!");
    Preconditions.checkArgument(
      brightness >= -MAXIMUM_BRIGHTNESS && brightness <= MAXIMUM_BRIGHTNESS,
      "Brightness %s must be between -255 and 255 inclusive!",
      brightness
    );
    this.contrast = contrast;
    this.brightness = brightness;
  }

  /**
   * Scales and shifts every channel of the frame in place, clamping the results to the range from 0 to 255.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   * @since 1.0.0
   */
  @Override
  protected boolean modifyMat(final Mat matrix) {
    matrix.convertTo(matrix, -1, this.contrast, this.brightness);

    return true;
  }
}
