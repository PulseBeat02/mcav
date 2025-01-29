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

import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * A filter that displays the current frame rate on the video.
 */
public class FPSFilter extends MatVideoFilter {

  private static final Scalar BLACK = new Scalar(0);
  private static final Point POSITION = new Point(10, 20);

  private long lastFrameTime;

  /**
   * Creates a new FPSFilter instance.
   */
  public FPSFilter() {
    this.lastFrameTime = System.currentTimeMillis();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final long current = System.currentTimeMillis();
    final long elapsed = current - this.lastFrameTime;
    if (elapsed <= 0) {
      return;
    }

    final int frameRate = Math.toIntExact(1000 / elapsed);
    this.lastFrameTime = current;

    final String text = "Frame Rate: " + frameRate + " FPS";
    opencv_imgproc.putText(mat, text, POSITION, opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.25, BLACK);
  }
}
