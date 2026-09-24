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
package me.brandonli.mcav.browser.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks center sampling on nonuniform images and the read-only recorder contract. */
final class FramesTest {

  @ParameterizedTest
  @CsvSource({ "4,2", "3,5", "5,2" })
  void recordsTheGeometricCenterWithoutChangingTheImage(final int width, final int height) {
    final int blue = 0xFF0000FF;
    final int red = 0xFFFF0000;
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, blue);
    final int centerX = width / 2;
    final int centerY = height / 2;
    pixels[centerY * width + centerX] = red;
    final int[] expectedPixels = pixels.clone();
    final VideoAttachableCallback callback = VideoAttachableCallback.create();
    final Frames frames = Frames.attach(callback);
    final VideoPipelineStep pipeline = callback.retrieve();
    final VideoFilter recorder = pipeline.getFilter();
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, width, height)) {
      final boolean changed = recorder.applyFilter(image, metadata);
      final int[] after = image.getPixels();
      final Frames.Frame frame = frames.requireLast();
      final int center = frame.getCenter();
      final boolean showsRed = frames.lastShows(0xFF0000);
      final boolean showsBlue = frames.lastShows(0x0000FF);
      assertFalse(changed);
      assertArrayEquals(expectedPixels, after);
      assertEquals(0xFF0000, center);
      assertTrue(showsRed);
      assertFalse(showsBlue);
    }
  }
}
