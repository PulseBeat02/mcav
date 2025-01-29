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

import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

public class FaceDetectionFilter extends MatVideoFilter {

  private final CascadeClassifier faceCascade;
  private final Scalar color;
  private final int thickness;

  public FaceDetectionFilter(final String faceCascadePath, final double[] color, final int thickness) {
    this.faceCascade = new CascadeClassifier(faceCascadePath);
    this.color = new Scalar(color);
    this.thickness = thickness;
  }

  public FaceDetectionFilter(final double[] color, final int thickness) {
    this.faceCascade = new CascadeClassifier();
    this.color = new Scalar(color);
    this.thickness = thickness;
  }

  @Override
  void modifyMat(final Mat mat) {
    final MatOfRect faces = new MatOfRect();
    this.faceCascade.detectMultiScale(mat, faces);
    final Rect[] facesArray = faces.toArray();
    for (final Rect face : facesArray) {
      Imgproc.rectangle(mat, face.tl(), face.br(), this.color, this.thickness);
    }
  }
}
