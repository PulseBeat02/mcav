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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import me.brandonli.mcav.utils.opencv.ImageUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

/**
 * Draws a rectangle around every face found in a frame, using an OpenCV Haar cascade such as
 * {@code haarcascade_frontalface_default.xml}. Detection runs on a grayscale copy of the frame.
 *
 * <p>Face detection needs the object detection natives of OpenCV, which link GTK 2 on Linux and are missing on many
 * servers. Check {@link me.brandonli.mcav.MCAVApi#hasCapability(me.brandonli.mcav.capability.Capability)} with
 * {@link me.brandonli.mcav.capability.Capability#FACE_DETECTION} before creating the filter; without the
 * natives the constructors throw an {@link IllegalStateException} that names the reason instead of a linkage error.
 *
 * <p>The grayscale matrix and the list of detections are kept between frames, so detection allocates no frame-sized
 * matrix per frame; both are freed when the filter is garbage collected. OpenCV's cascade classifier must not be used by
 * two threads at once, so one filter attached to several pipelines detects faces in one frame at a time.
 */
public class FaceDetectionFilter extends MatVideoFilter {

  private final CascadeClassifier classifier;
  private final Scalar color;
  private final Mat gray;
  private final RectVector faces;

  /**
   * Constructs a new face detection filter.
   *
   * @param cascadeFile the path of the Haar cascade XML file
   * @param color       the blue, green, and red components of the rectangle color, from 0 to 255
   * @throws IllegalArgumentException if the cascade file does not exist or cannot be loaded
   * @throws IllegalStateException if face detection is not available on this system, because the OpenCV object
   *                               detection natives cannot be loaded; on Linux they need GTK 2
   */
  public FaceDetectionFilter(final Path cascadeFile, final double[] color) {
    Preconditions.checkNotNull(cascadeFile, "Cascade file must not be null");
    this.classifier = createClassifier(cascadeFile);
    this.color = ImageUtils.toScalar(color);
    this.gray = new Mat();
    this.faces = new RectVector();
  }

  /**
   * Constructs a new face detection filter.
   *
   * @param cascadeFile the path of the Haar cascade XML file
   * @param color       the blue, green, and red components of the rectangle color, from 0 to 255
   * @throws IllegalArgumentException if the cascade file does not exist or cannot be loaded
   * @throws IllegalStateException if face detection is not available on this system, because the OpenCV object
   *                               detection natives cannot be loaded; on Linux they need GTK 2
   */
  public FaceDetectionFilter(final String cascadeFile, final double[] color) {
    Preconditions.checkNotNull(cascadeFile, "Cascade file must not be null");
    final Path path = Path.of(cascadeFile);
    this.classifier = createClassifier(path);
    this.color = ImageUtils.toScalar(color);
    this.gray = new Mat();
    this.faces = new RectVector();
  }

  private static CascadeClassifier createClassifier(final Path cascadeFile) {
    final boolean exists = Files.isRegularFile(cascadeFile);
    Preconditions.checkArgument(exists, "Cascade file does not exist: %s", cascadeFile);
    return loadClassifier(cascadeFile, CascadeClassifier::new);
  }

  /**
   * Loads a cascade classifier.
   *
   * @param cascadeFile the cascade file
   * @param factory     creates the classifier from the path of the file
   * @return the classifier
   * @throws IllegalArgumentException if the file is not a valid cascade
   * @throws IllegalStateException    if the object detection natives cannot be loaded
   */
  @VisibleForTesting
  static CascadeClassifier loadClassifier(final Path cascadeFile, final Function<String, CascadeClassifier> factory) {
    final String cascadePath = cascadeFile.toString();
    final CascadeClassifier classifier;
    try {
      classifier = factory.apply(cascadePath);
    } catch (final LinkageError error) {
      final String reason = error.getMessage();
      throw new IllegalStateException(
        "Face detection is not available, because the OpenCV object detection natives cannot be loaded: " + reason,
        error
      );
    } catch (final RuntimeException exception) {
      throw new IllegalArgumentException("Cascade file could not be loaded: " + cascadeFile, exception);
    }
    final boolean empty = classifier.empty();
    if (empty) {
      classifier.close();
      throw new IllegalArgumentException("Cascade file could not be loaded: " + cascadeFile);
    }
    return classifier;
  }

  /**
   * Detects faces on a grayscale copy of the frame and outlines each of them in the frame, in place. The copy and the
   * detections go into matrices the filter reuses, which is why only one frame is processed at a time.
   *
   * @param mat the 8-bit BGR matrix of the frame
   * @return true if at least one face was outlined, false if none was found and the frame was left untouched
   */
  @Override
  protected synchronized boolean modifyMat(final Mat mat) {
    opencv_imgproc.cvtColor(mat, this.gray, opencv_imgproc.COLOR_BGR2GRAY);
    this.classifier.detectMultiScale(this.gray, this.faces);
    final long count = this.faces.size();
    for (long i = 0; i < count; i++) {
      final Rect face = this.faces.get(i);
      opencv_imgproc.rectangle(mat, face, this.color);
    }
    return count > 0;
  }
}
