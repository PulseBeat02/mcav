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

import static org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

public class FPSFilter extends MatVideoFilter {

  private static final Scalar BLACK = new Scalar(0, 0, 0);
  private static final Point POSITION = new Point(10, 20);

  private long lastFrameTime;

  public FPSFilter() {
    this.lastFrameTime = System.currentTimeMillis();
  }

  @Override
  void modifyMat(final Mat mat) {
    final long current = System.currentTimeMillis();
    final long elapsed = current - this.lastFrameTime;
    final int frameRate = Math.toIntExact(1000 / elapsed);
    this.lastFrameTime = current;
    final String text = "Frame Rate: " + frameRate + " FPS";
    Imgproc.putText(mat, text, POSITION, FONT_HERSHEY_SIMPLEX, 0.25, BLACK, 1);
  }
}
