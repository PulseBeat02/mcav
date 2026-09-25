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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Objects;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.junit.jupiter.api.Test;

class FrameCanvasTest {

  private static byte[] bgra(final int width, final int height, final int blue, final int green, final int red) {
    final byte[] pixels = new byte[width * height * 4];
    for (int index = 0; index < pixels.length; index += 4) {
      pixels[index] = (byte) blue;
      pixels[index + 1] = (byte) green;
      pixels[index + 2] = (byte) red;
      pixels[index + 3] = (byte) 255;
    }
    return pixels;
  }

  private static int[] bgr(final ImageBuffer image, final int x, final int y) {
    final ByteBuffer pixels = image.getData();
    final int offset = (y * image.getWidth() + x) * 3;
    return new int[] { pixels.get(offset) & 0xFF, pixels.get(offset + 1) & 0xFF, pixels.get(offset + 2) & 0xFF };
  }

  private static ImageBuffer snapshot(final FrameCanvas canvas) {
    return Objects.requireNonNull(canvas.snapshot(), "an open canvas has a picture");
  }

  @Test
  void aNewCanvasIsWhite() {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3); final ImageBuffer image = snapshot(canvas)) {
      assertEquals(4, canvas.getWidth());
      assertEquals(3, canvas.getHeight());
      assertEquals(4, image.getWidth());
      assertEquals(3, image.getHeight());
      assertEquals(255, bgr(image, 3, 2)[0]);
      assertEquals(255, bgr(image, 3, 2)[2]);
    }
  }

  @Test
  void aRegionReplacesItsPixelsOnly() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      canvas.apply(new FrameRegion(4, 3, 1, 1, 2, 2, bgra(2, 2, 10, 20, 30)));
      try (final ImageBuffer image = snapshot(canvas)) {
        final int[] inside = bgr(image, 2, 2);
        assertEquals(10, inside[0]);
        assertEquals(20, inside[1]);
        assertEquals(30, inside[2]);
        assertEquals(255, bgr(image, 0, 0)[0]);
        assertEquals(255, bgr(image, 3, 1)[1]);
      }
    }
  }

  @Test
  void aRegionReadsOnlyTheBytesItNeedsFromALongerBuffer() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(2, 1)) {
      final byte[] longer = new byte[64];
      longer[0] = 1;
      longer[1] = 2;
      longer[2] = 3;
      canvas.apply(new FrameRegion(2, 1, 1, 0, 1, 1, longer));
      try (final ImageBuffer image = snapshot(canvas)) {
        assertEquals(1, bgr(image, 1, 0)[0]);
        assertEquals(3, bgr(image, 1, 0)[2]);
      }
    }
  }

  @Test
  void aRegionOfAnotherPageIsAProtocolError() {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      assertThrows(ProtocolException.class, () -> canvas.apply(new FrameRegion(5, 3, 0, 0, 1, 1, new byte[4])));
      final ProtocolException height = assertThrows(ProtocolException.class, () ->
        canvas.apply(new FrameRegion(4, 2, 0, 0, 1, 1, new byte[4]))
      );
      assertTrue(height.getMessage().contains("4x2"));
    }
  }

  @Test
  void aClosedCanvasIgnoresRegionsAndHasNoPicture() throws ProtocolException {
    final FrameCanvas canvas = new FrameCanvas(2, 2);
    canvas.close();
    canvas.close();
    canvas.apply(new FrameRegion(2, 2, 0, 0, 1, 1, new byte[4]));
    assertNull(canvas.snapshot());
  }

  @Test
  void everyRowOfARegionLandsOnItsOwnRow() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      final byte[] pixels = new byte[2 * 2 * 4];
      System.arraycopy(bgra(2, 1, 10, 20, 30), 0, pixels, 0, 8);
      System.arraycopy(bgra(2, 1, 40, 50, 60), 0, pixels, 8, 8);
      canvas.apply(new FrameRegion(4, 3, 1, 1, 2, 2, pixels));
      try (final ImageBuffer image = snapshot(canvas)) {
        assertEquals(10, bgr(image, 1, 1)[0]);
        assertEquals(40, bgr(image, 1, 2)[0]);
        assertEquals(60, bgr(image, 2, 2)[2]);
      }
    }
  }

  @Test
  void closingReleasesTheNativePicture() {
    final FrameCanvas canvas = new FrameCanvas(4, 3);
    assertFalse(canvas.getPage().isNull());
    canvas.close();
    assertTrue(canvas.getPage().isNull());
  }
}
