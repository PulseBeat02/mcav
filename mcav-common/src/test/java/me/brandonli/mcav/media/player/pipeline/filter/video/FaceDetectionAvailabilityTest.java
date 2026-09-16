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

import static me.brandonli.mcav.media.ResourceAssertions.assertThrowsWhileOpening;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests how {@link FaceDetectionFilter} reports machines that cannot load the object detection natives. The tests
 * never load those natives, so they run everywhere.
 */
final class FaceDetectionAvailabilityTest {

  private static final Path CASCADE = Path.of("haarcascade_frontalface_default.xml");

  @Test
  void explainsThatFaceDetectionIsUnavailableWhenTheNativesCannotBeLinked() {
    final UnsatisfiedLinkError missingGtk = new UnsatisfiedLinkError("libgtk-x11-2.0.so.0: cannot open shared object file");
    final IllegalStateException exception = assertThrowsWhileOpening(IllegalStateException.class, () ->
      FaceDetectionFilter.loadClassifier(CASCADE, _ -> {
        throw missingGtk;
      })
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean explainsUnavailability = message.contains("Face detection is not available");
    final boolean namesTheMissingLibrary = message.contains("libgtk-x11-2.0");
    assertTrue(explainsUnavailability, message);
    assertTrue(namesTheMissingLibrary, message);
    assertSame(missingGtk, cause);
  }

  @Test
  void reportsFilesThatAreNotCascades() {
    final RuntimeException openCvError = new RuntimeException("Can't open file");
    final IllegalArgumentException exception = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      FaceDetectionFilter.loadClassifier(CASCADE, _ -> {
        throw openCvError;
      })
    );
    final Throwable cause = exception.getCause();
    assertSame(openCvError, cause);
  }
}
