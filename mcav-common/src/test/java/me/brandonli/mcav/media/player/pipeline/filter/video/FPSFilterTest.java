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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

/** Verifies the measured rate and its visible label against controlled time windows. */
final class FPSFilterTest {

  @Test
  void updatesAtOneSecondAndDividesByTheActualElapsedTime() {
    final AtomicLong clock = new AtomicLong(0L);
    final FPSFilter filter = new FPSFilter(clock::get);
    clock.set(999_999_999L);
    assertLabel(filter, "0 fps");
    clock.set(1_000_000_000L);
    assertLabel(filter, "2 fps");
    clock.set(1_500_000_000L);
    assertLabel(filter, "2 fps");
    clock.set(3_000_000_000L);
    assertLabel(filter, "1 fps");
  }

  private static void assertLabel(final FPSFilter filter, final String expectedLabel) {
    try (
      final Scalar background = new Scalar(80.0, 80.0, 80.0, 0.0);
      final Scalar black = new Scalar(0.0);
      final Scalar white = new Scalar(255.0, 255.0, 255.0, 0.0);
      final Point position = new Point(10, 24);
      final Mat actual = new Mat(40, 120, opencv_core.CV_8UC3, background);
      final Mat expected = new Mat(40, 120, opencv_core.CV_8UC3, background)
    ) {
      opencv_imgproc.putText(
        expected,
        expectedLabel,
        position,
        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
        0.6,
        black,
        3,
        opencv_imgproc.LINE_AA,
        false
      );
      opencv_imgproc.putText(
        expected,
        expectedLabel,
        position,
        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
        0.6,
        white,
        1,
        opencv_imgproc.LINE_AA,
        false
      );
      final boolean modified = filter.modifyMat(actual);
      final ByteBuffer actualPixels = actual.createBuffer();
      final ByteBuffer expectedPixels = expected.createBuffer();
      assertTrue(modified);
      assertEquals(expectedPixels, actualPixels, expectedLabel);
    }
  }
}
