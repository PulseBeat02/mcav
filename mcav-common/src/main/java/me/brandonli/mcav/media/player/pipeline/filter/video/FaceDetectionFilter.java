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
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

/**
 * A video filter that detects faces in a video stream using OpenCV's Haar Cascade Classifier.
 */
public class FaceDetectionFilter extends MatVideoFilter {

  private final CascadeClassifier faceCascade;
  private final Scalar color;

  /**
   * Constructs a FaceDetectionFilter with the specified Haar Cascade file path and color for face rectangles.
   *
   * @param faceCascadePath The path to the Haar Cascade XML file for face detection.
   * @param color           The color to use for drawing rectangles around detected faces, represented as an array of doubles.
   */
  public FaceDetectionFilter(final String faceCascadePath, final double[] color) {
    this.faceCascade = new CascadeClassifier(faceCascadePath);
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * Constructs a FaceDetectionFilter with the specified color for face rectangles.
   *
   * @param color The color to use for drawing rectangles around detected faces, represented as an array of doubles.
   */
  public FaceDetectionFilter(final double[] color) {
    this.faceCascade = new CascadeClassifier();
    this.color = ImageUtils.toScalar(color);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  void modifyMat(final Mat mat) {
    final RectVector faces = new RectVector();
    this.faceCascade.detectMultiScale(mat, faces);
    final Rect[] facesArray = faces.get();
    for (final Rect face : facesArray) {
      opencv_imgproc.rectangle(mat, face.tl(), face.br(), this.color);
    }
  }
}
