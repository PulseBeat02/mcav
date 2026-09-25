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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the conversion of Java images into image buffers, the path every frame of a VNC server takes: the server
 * decides the size of its screen and the image type the client decodes it into, and a frame may be a view into a larger
 * image. Whatever the type, size and view, every pixel of the buffer is the color the image itself reports, made
 * opaque, both for a new buffer and for one that is reused. The images are built from the fuzzer's data; there is no
 * artefact of a VNC screen in the repository to seed them with.
 */
@Tag("fuzz")
final class BufferedImageConversionFuzzTest {

  private static final int[] TYPES = {
    BufferedImage.TYPE_INT_RGB,
    BufferedImage.TYPE_INT_ARGB,
    BufferedImage.TYPE_INT_ARGB_PRE,
    BufferedImage.TYPE_INT_BGR,
    BufferedImage.TYPE_3BYTE_BGR,
    BufferedImage.TYPE_4BYTE_ABGR,
    BufferedImage.TYPE_4BYTE_ABGR_PRE,
    BufferedImage.TYPE_USHORT_565_RGB,
    BufferedImage.TYPE_USHORT_555_RGB,
    BufferedImage.TYPE_BYTE_GRAY,
    BufferedImage.TYPE_USHORT_GRAY,
    BufferedImage.TYPE_BYTE_BINARY,
    BufferedImage.TYPE_BYTE_INDEXED,
  };
  private static final int MAX_SIDE = 48;

  @FuzzTest(maxDuration = "30s")
  void everyPixelIsTheColorTheImageReports(final FuzzedDataProvider data) {
    final int type = data.pickValue(TYPES);
    final int width = data.consumeInt(1, MAX_SIDE);
    final int height = data.consumeInt(1, MAX_SIDE);
    final BufferedImage whole = new BufferedImage(width, height, type);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        whole.setRGB(x, y, data.consumeInt());
      }
    }
    final int viewX = data.consumeInt(0, width - 1);
    final int viewY = data.consumeInt(0, height - 1);
    final int viewWidth = data.consumeInt(1, width - viewX);
    final int viewHeight = data.consumeInt(1, height - viewY);
    final boolean useView = data.consumeBoolean();
    final BufferedImage frame = useView ? whole.getSubimage(viewX, viewY, viewWidth, viewHeight) : whole;

    try (final ImageBuffer created = ImageBuffer.image(frame)) {
      assertSamePixels(frame, created);
      final BufferedImage replacement = new BufferedImage(frame.getWidth(), frame.getHeight(), type);
      for (int y = 0; y < replacement.getHeight(); y++) {
        for (int x = 0; x < replacement.getWidth(); x++) {
          replacement.setRGB(x, y, ~frame.getRGB(x, y));
        }
      }
      created.setAsBufferedImage(replacement);
      assertSamePixels(replacement, created);
    }
  }

  private static void assertSamePixels(final BufferedImage image, final ImageBuffer buffer) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int bufferWidth = buffer.getWidth();
    final int bufferHeight = buffer.getHeight();
    assertEquals(width, bufferWidth, "width");
    assertEquals(height, bufferHeight, "height");
    final int[] pixels = buffer.getPixels();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int expected = image.getRGB(x, y) | 0xFF000000;
        final int actual = pixels[y * width + x];
        final int column = x;
        final int row = y;
        assertEquals(
          expected,
          actual,
          () -> "pixel " + column + "," + row + " of a " + width + "x" + height + " image of type " + image.getType()
        );
      }
    }
  }
}
