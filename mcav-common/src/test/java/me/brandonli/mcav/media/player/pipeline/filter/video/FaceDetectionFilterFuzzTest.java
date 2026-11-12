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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.testing.OpenCvModules;
import org.bytedeco.opencv.presets.opencv_objdetect;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class FaceDetectionFilterFuzzTest {

  private static final double[] RED = { 0.0, 0.0, 255.0 };

  private static final String CASCADE_PREFIX = "mcav-cascade";
  private static final String CASCADE_SUFFIX = ".xml";

  @BeforeEach
  void requireObjectDetection() {
    final boolean available = OpenCvModules.canLoad(opencv_objdetect.class);
    Assumptions.assumeTrue(available, "The OpenCV object detection natives cannot be loaded here, because GTK 2 is missing!");
  }

  @FuzzTest(maxDuration = "30s")
  void shouldLoadTheCascadeOrRefuseTheFile(final byte[] cascade) throws IOException {
    final Path file = Files.createTempFile(CASCADE_PREFIX, CASCADE_SUFFIX);
    try {
      Files.write(file, cascade);
      this.createFilter(file);
    } finally {
      Files.delete(file);
    }
  }

  private void createFilter(final Path file) {
    try {
      final FaceDetectionFilter _ = new FaceDetectionFilter(file, RED);
    } catch (final IllegalArgumentException exception) {
      // a file that is not a cascade; any other failure is a finding
    }
  }
}
