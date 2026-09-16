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
import me.brandonli.mcav.testing.Images;
import me.brandonli.mcav.testing.OpenCvModules;
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
      final boolean detected = filter.applyFilter(image);
      final int[] after = image.getPixels();
      final boolean changed = !Arrays.equals(before, after);
      assertTrue(detected);
      assertTrue(changed, "the detections are outlined");
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
  void rejectsCascadesThatCannotBeLoaded() throws IOException {
    final Path missing = this.directory.resolve("missing.xml");
    final Path unparsable = this.directory.resolve("unparsable.xml");
    final Path withoutCascade = this.directory.resolve("without-cascade.xml");
    Files.writeString(unparsable, "");
    Files.writeString(withoutCascade, "<?xml version=\"1.0\"?>\n<opencv_storage>\n<unrelated>1</unrelated>\n</opencv_storage>\n");
    assertThrows(IllegalArgumentException.class, () -> new FaceDetectionFilter(missing, RED));
    assertThrows(IllegalArgumentException.class, () -> new FaceDetectionFilter(unparsable, RED));
    assertThrows(IllegalArgumentException.class, () -> new FaceDetectionFilter(withoutCascade, RED));
    assertThrows(NullPointerException.class, () -> new FaceDetectionFilter((Path) null, RED));
  }
}
