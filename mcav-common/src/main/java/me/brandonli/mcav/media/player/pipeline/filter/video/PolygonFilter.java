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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

public class PolygonFilter extends MatVideoFilter {

  private final boolean isClosed;
  private final int thickness;
  private final List<MatOfPoint> contours;
  private final Scalar color;

  public PolygonFilter(
    final List<me.brandonli.mcav.utils.immutable.Point> points,
    final boolean isClosed,
    final double[] scalarColor,
    final int thickness
  ) {
    this.isClosed = isClosed;
    this.thickness = thickness;
    final MatOfPoint matOfPoint = new MatOfPoint();
    matOfPoint.fromList(points.stream().map(p -> new Point(p.getX(), p.getY())).collect(Collectors.toList()));
    this.contours = new ArrayList<>();
    this.contours.add(matOfPoint);
    this.color = new Scalar(scalarColor);
  }

  @Override
  void modifyMat(final Mat mat) {
    Imgproc.polylines(mat, this.contours, this.isClosed, this.color, this.thickness);
  }
}
