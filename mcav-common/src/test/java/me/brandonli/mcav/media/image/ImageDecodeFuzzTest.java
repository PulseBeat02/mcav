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
package me.brandonli.mcav.media.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the decoding of encoded images, which {@code /mcav image} feeds with whatever a URL or a file holds: the bytes
 * either become an image of a positive size with one pixel per pixel, or are refused with the
 * {@link IllegalArgumentException} {@link ImageBuffer#bytes(byte[])} documents. The seeds are the textures of the test
 * resources of mcav-bukkit and the same textures converted to the other formats OpenCV reads.
 */
@Tag("fuzz")
final class ImageDecodeFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void decodesAnImageOrRefusesTheBytes(final byte[] encoded) {
    final ImageBuffer image;
    try {
      image = ImageBuffer.bytes(encoded);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    try {
      final int width = image.getWidth();
      final int height = image.getHeight();
      final boolean positive = width > 0 && height > 0;
      assertTrue(positive, () -> "a decoded image of " + width + "x" + height);
      final int[] pixels = image.getPixels();
      final long expected = (long) width * height;
      assertEquals(expected, pixels.length, "one pixel per pixel");
    } finally {
      image.release();
    }
  }
}
