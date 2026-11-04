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
import me.brandonli.mcav.utils.natives.NativeLoadingException;
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
 * natives the constructors throw a {@link NativeLoadingException} that names the reason instead of a linkage error.
 *
 * <p>The grayscale matrix and the list of detections are kept between frames, so detection allocates no frame-sized
 * matrix per frame; both are freed when the filter is garbage collected. OpenCV's cascade classifier must not be used by
 * two threads at once, so one filter attached to several pipelines detects faces in one frame at a time.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class FaceDetectionFilter extends MatVideoFilter {

  private static final String CASCADE_FILE_NULL_MESSAGE = "Cascade file must not be null!";
  private static final String UNLOADABLE_CASCADE_MESSAGE = "Cascade file %s could not be loaded!";

  private final CascadeClassifier classifier;
  private final Scalar color;
  private final Mat gray;
  private final RectVector faces;

  /**
   * Constructs a new face detection filter.
   *
   * @param cascadeFile the path of the Haar cascade XML file
   * @param color       the blue, green, and red components of the rectangle color, normally 0 to 255; the non-null array is copied
   * @throws IllegalArgumentException if the cascade file does not exist or cannot be loaded
   * @throws NativeLoadingException   if face detection is not available on this system, because the OpenCV object
   *                                  detection natives cannot be loaded; on Linux they need GTK 2
   * @throws NullPointerException     if {@code cascadeFile} or {@code color} is null
   * @since 1.0.0
   */
  public FaceDetectionFilter(final Path cascadeFile, final double[] color) {
    Preconditions.checkNotNull(cascadeFile, CASCADE_FILE_NULL_MESSAGE);

    this.classifier = createClassifier(cascadeFile);
    this.color = ImageUtils.toScalar(color);
    this.gray = new Mat();
    this.faces = new RectVector();
  }

  /**
   * Constructs a new face detection filter.
   *
   * @param cascadeFile the path of the Haar cascade XML file
   * @param color       the blue, green, and red components of the rectangle color, normally 0 to 255; the non-null array is copied
   * @throws IllegalArgumentException           if the cascade file does not exist or cannot be loaded
   * @throws NativeLoadingException             if face detection is not available on this system, because the OpenCV object
   *                                            detection natives cannot be loaded; on Linux they need GTK 2
   * @throws NullPointerException               if {@code cascadeFile} or {@code color} is null
   * @throws java.nio.file.InvalidPathException if the path string is invalid
   * @since 1.0.0
   */
  public FaceDetectionFilter(final String cascadeFile, final double[] color) {
    Preconditions.checkNotNull(cascadeFile, CASCADE_FILE_NULL_MESSAGE);

    final Path path = Path.of(cascadeFile);
    this.classifier = createClassifier(path);
    this.color = ImageUtils.toScalar(color);
    this.gray = new Mat();
    this.faces = new RectVector();
  }

  private static CascadeClassifier createClassifier(final Path cascadeFile) {
    final boolean exists = Files.isRegularFile(cascadeFile);
    Preconditions.checkArgument(exists, "Cascade file %s does not exist!", cascadeFile);

    return loadClassifier(cascadeFile, CascadeClassifier::new);
  }

  @VisibleForTesting
  static CascadeClassifier loadClassifier(final Path cascadeFile, final Function<String, CascadeClassifier> factory) {
    final String cascadePath = cascadeFile.toString();
    final CascadeClassifier classifier;
    try {
      classifier = factory.apply(cascadePath);
    } catch (final LinkageError exception) {
      final String reason = exception.getMessage();
      final String message =
        "Face detection is not available, because the error %s stopped the OpenCV object detection natives from loading!".formatted(reason);
      throw new NativeLoadingException(message, exception);
    } catch (final RuntimeException exception) {
      final String message = UNLOADABLE_CASCADE_MESSAGE.formatted(cascadeFile);
      throw new IllegalArgumentException(message, exception);
    }

    final boolean empty = classifier.empty();
    if (empty) {
      classifier.close();
      final String message = UNLOADABLE_CASCADE_MESSAGE.formatted(cascadeFile);
      throw new IllegalArgumentException(message);
    }

    return classifier;
  }

  /**
   * Detects faces on a grayscale copy of the frame and outlines each of them in the frame, in place. The copy and the
   * detections go into matrices the filter reuses, which is why only one frame is processed at a time.
   *
   * @param matrix the 8-bit BGR matrix of the frame
   * @return true if at least one face was outlined, false if none was found and the frame was left untouched
   * @since 1.0.0
   */
  @Override
  protected synchronized boolean modifyMat(final Mat matrix) {
    opencv_imgproc.cvtColor(matrix, this.gray, opencv_imgproc.COLOR_BGR2GRAY);
    this.classifier.detectMultiScale(this.gray, this.faces);
    final long count = this.faces.size();
    for (long faceIndex = 0; faceIndex < count; faceIndex++) {
      final Rect face = this.faces.get(faceIndex);
      opencv_imgproc.rectangle(matrix, face, this.color);
    }

    return count > 0;
  }
}
