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
import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * Draws a line of text onto every frame with one of the built-in OpenCV fonts.
 */
public class TextFilter extends MatVideoFilter {

  /**
   * The default font, a plain sans-serif font.
   */
  public static final int DEFAULT_FONT = opencv_imgproc.FONT_HERSHEY_SIMPLEX;

  private final String text;
  private final Point position;
  private final int font;
  private final double scale;
  private final Scalar color;

  /**
   * Constructs a new text filter.
   *
   * @param text  the text to draw
   * @param x     the x coordinate of the left end of the text baseline
   * @param y     the y coordinate of the text baseline
   * @param font  the font, one of the {@code FONT_HERSHEY_} constants of {@link opencv_imgproc}
   * @param scale the size of the text relative to the base size of the font, which must be positive
   * @param color the blue, green, and red components of the color, from 0 to 255
   */
  public TextFilter(final String text, final int x, final int y, final int font, final double scale, final double[] color) {
    Preconditions.checkNotNull(text, "Text must not be null");
    Preconditions.checkArgument(scale > 0, "Scale must be positive");
    this.text = text;
    this.position = new Point(x, y);
    this.font = font;
    this.scale = scale;
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Draws the text onto the frame in place. Parts of the text outside of the frame are cut off.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true, because the frame may have changed
   */
  @Override
  protected boolean modifyMat(final Mat mat) {
    opencv_imgproc.putText(mat, this.text, this.position, this.font, this.scale, this.color);
    return true;
  }
}
