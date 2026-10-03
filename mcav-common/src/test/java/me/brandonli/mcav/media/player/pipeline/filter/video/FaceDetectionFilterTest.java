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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.testing.Images;
import me.brandonli.mcav.testing.OpenCvModules;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.bytedeco.opencv.presets.opencv_objdetect;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FaceDetectionFilter} with cascades that accept or reject every window, so the result does not depend
 * on how well a real cascade recognizes a picture. Uniform areas are never detected, so the frames are noise.
 */
final class FaceDetectionFilterTest {

  private static final double[] RED = { 0.0, 0.0, 255.0 };

  @TempDir
  private Path directory;

  @BeforeEach
  void requireObjectDetection() {
    final boolean available = OpenCvModules.canLoad(opencv_objdetect.class);
    Assumptions.assumeTrue(available, "The OpenCV object detection natives cannot be loaded here (GTK 2 is missing)");
  }

  private Path copyCascade(final String name) throws IOException {
    final Path target = this.directory.resolve(name);
    final ClassLoader loader = FaceDetectionFilterTest.class.getClassLoader();
    try (final InputStream stream = loader.getResourceAsStream("cascades/" + name)) {
      assertNotNull(stream, "missing test resource " + name);
      Files.copy(stream, target);
    }
    return target;
  }

  @Test
  void marksDetectionsWithRectangles() throws IOException {
    final Path cascade = this.copyCascade("accept-everything.xml");
    final FaceDetectionFilter filter = new FaceDetectionFilter(cascade, RED);
    try (final ImageBuffer image = Images.noise(96, 96, 42L)) {
      final int[] original = image.getPixels();
      final int[] before = original.clone();
      final int[] expected = before.clone();
      try (
        final CascadeClassifier detector = new CascadeClassifier(cascade.toString());
        final Mat gray = new Mat();
        final RectVector rectangles = new RectVector()
      ) {
        opencv_imgproc.cvtColor(((MatImageBuffer) image).getMat(), gray, opencv_imgproc.COLOR_BGR2GRAY);
        detector.detectMultiScale(gray, rectangles);
        assertTrue(rectangles.size() > 0, "the fixture must produce detections");
        for (long index = 0; index < rectangles.size(); index++) {
          final Rect rectangle = rectangles.get(index);
          final int left = rectangle.x();
          final int top = rectangle.y();
          final int right = left + rectangle.width() - 1;
          final int bottom = top + rectangle.height() - 1;
          for (int row = Math.max(0, top); row <= Math.min(95, bottom); row++) {
            for (int column = Math.max(0, left); column <= Math.min(95, right); column++) {
              if (row == top || row == bottom || column == left || column == right) {
                expected[row * 96 + column] = 0xFFFF0000;
              }
            }
          }
        }
      }
      final boolean detected = filter.applyFilter(image);
      final int[] after = image.getPixels();
      final boolean changed = !Arrays.equals(before, after);
      assertTrue(detected);
      assertTrue(changed, "the detections are outlined");
      assertArrayEquals(expected, after, "every detection has its red outline and every other pixel is retained");
    }
  }

  @Test
  void reportsFramesWithoutDetections() throws IOException {
    final Path cascade = this.copyCascade("reject-everything.xml");
    final String cascadePath = cascade.toString();
    final FaceDetectionFilter filter = new FaceDetectionFilter(cascadePath, RED);
    try (final ImageBuffer image = Images.noise(96, 96, 7L)) {
      final int[] original = image.getPixels();
      final int[] before = original.clone();
      final boolean detected = filter.applyFilter(image);
      final int[] after = image.getPixels();
      final boolean unchanged = Arrays.equals(before, after);
      assertFalse(detected);
      assertTrue(unchanged);
    }
  }

  @Test
  void rejectsAndClosesAClassifierWhoseLoadReturnedFalse() {
    final Path path = this.directory.resolve("rejected.xml");
    try (final CascadeClassifier classifier = new CascadeClassifier()) {
      assertThrows(IllegalArgumentException.class, () -> FaceDetectionFilter.loadClassifier(path, _ -> classifier));
      final boolean closed = classifier.isNull();
      assertTrue(closed, "failed native classifier ownership must be released before reporting failure");
    }
  }

  @Test
  void rejectsCascadesThatCannotBeLoaded() throws IOException {
    final Path missing = this.directory.resolve("missing.xml");
    final Path unparsable = this.directory.resolve("unparsable.xml");
    final Path withoutCascade = this.directory.resolve("without-cascade.xml");
    Files.writeString(unparsable, "");
    Files.writeString(withoutCascade, "<?xml version=\"1.0\"?>\n<opencv_storage>\n<unrelated>1</unrelated>\n</opencv_storage>\n");
    final IllegalArgumentException missingFailure = assertThrows(IllegalArgumentException.class, () ->
      new FaceDetectionFilter(missing, RED)
    );
    final String missingMessage = missingFailure.getMessage();
    assertEquals("Cascade file does not exist: " + missing, missingMessage);
    assertThrows(IllegalArgumentException.class, () -> new FaceDetectionFilter(unparsable, RED));
    assertThrows(IllegalArgumentException.class, () -> new FaceDetectionFilter(withoutCascade, RED));
    assertThrows(NullPointerException.class, () -> new FaceDetectionFilter((Path) null, RED));
  }
}
