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

import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * A video filter that draws text on video frames.
 */
public class TextFilter extends MatVideoFilter {

  private final String text;
  private final int fontFace;
  private final double fontScale;
  private final Point position;
  private final Scalar color;

  /**
   * Constructs a TextFilter to draw text on a video frame.
   *
   * @param text        The text to draw.
   * @param x           The x-coordinate of the text position.
   * @param y           The y-coordinate of the text position.
   * @param fontFace    The font face to use for the text.
   * @param fontScale   The scale factor for the font size.
   * @param scalarColor The color of the text in scalar format (BGR).
   */
  public TextFilter(final String text, final int x, final int y, final int fontFace, final double fontScale, final double[] scalarColor) {
    this.text = text;
    this.fontFace = fontFace;
    this.fontScale = fontScale;
    this.position = new Point(x, y);
    this.color = ImageUtils.toScalar(scalarColor);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  boolean modifyMat(final Mat mat) {
    opencv_imgproc.putText(mat, this.text, this.position, this.fontFace, this.fontScale, this.color);
    return true;
  }
}
