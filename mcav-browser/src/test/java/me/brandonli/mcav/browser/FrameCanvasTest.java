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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class FrameCanvasTest {

  private static final int CHANNEL_MASK = 0xFF;
  private static final int FULL_CHANNEL = 255;
  private static final int REGION_GREEN = 20;
  private static final int REGION_RED = 30;

  private byte[] bgra(final int width, final int height, final int blue, final int green, final int red) {
    final byte[] pixels = new byte[width * height * 4];
    for (int index = 0; index < pixels.length; index += 4) {
      pixels[index] = (byte) blue;
      pixels[index + 1] = (byte) green;
      pixels[index + 2] = (byte) red;
      pixels[index + 3] = (byte) FULL_CHANNEL;
    }

    return pixels;
  }

  private int[] bgr(final ImageBuffer image, final int column, final int row) {
    final ByteBuffer pixels = image.getData();
    final int offset = (row * image.getWidth() + column) * 3;
    return new int[] { pixels.get(offset) & CHANNEL_MASK, pixels.get(offset + 1) & CHANNEL_MASK, pixels.get(offset + 2) & CHANNEL_MASK };
  }

  private ImageBuffer snapshot(final FrameCanvas canvas) {
    return Objects.requireNonNull(canvas.snapshot(), "An open canvas must have a picture!");
  }

  @Test
  void shouldStartWhite() {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3); final ImageBuffer image = this.snapshot(canvas)) {
      final int canvasWidth = canvas.getWidth();
      assertEquals(4, canvasWidth);

      final int canvasHeight = canvas.getHeight();
      assertEquals(3, canvasHeight);

      final int imageWidth = image.getWidth();
      assertEquals(4, imageWidth);

      final int imageHeight = image.getHeight();
      assertEquals(3, imageHeight);

      final int cornerBlue = this.bgr(image, 3, 2)[0];
      assertEquals(FULL_CHANNEL, cornerBlue);

      final int cornerRed = this.bgr(image, 3, 2)[2];
      assertEquals(FULL_CHANNEL, cornerRed);
    }
  }

  @Test
  void shouldReplaceOnlyThePixelsOfARegion() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      canvas.apply(new FrameRegion(4, 3, 1, 1, 2, 2, this.bgra(2, 2, 10, REGION_GREEN, REGION_RED)));
      try (final ImageBuffer image = this.snapshot(canvas)) {
        final int[] inside = this.bgr(image, 2, 2);
        final int insideBlue = inside[0];
        assertEquals(10, insideBlue);

        final int insideGreen = inside[1];
        assertEquals(REGION_GREEN, insideGreen);

        final int insideRed = inside[2];
        assertEquals(REGION_RED, insideRed);

        final int outsideBlue = this.bgr(image, 0, 0)[0];
        assertEquals(FULL_CHANNEL, outsideBlue);

        final int outsideGreen = this.bgr(image, 3, 1)[1];
        assertEquals(FULL_CHANNEL, outsideGreen);
      }
    }
  }

  @Test
  void shouldReadOnlyTheBytesARegionNeedsFromALongerBuffer() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(2, 1)) {
      final byte[] longer = new byte[64];
      longer[0] = 1;
      longer[1] = 2;
      longer[2] = 3;
      canvas.apply(new FrameRegion(2, 1, 1, 0, 1, 1, longer));
      try (final ImageBuffer image = this.snapshot(canvas)) {
        final int blue = this.bgr(image, 1, 0)[0];
        assertEquals(1, blue);

        final int red = this.bgr(image, 1, 0)[2];
        assertEquals(3, red);
      }
    }
  }

  @Test
  void shouldTreatARegionOfAnotherPageAsAProtocolError() {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      assertThrows(ProtocolException.class, () -> canvas.apply(new FrameRegion(5, 3, 0, 0, 1, 1, new byte[4])));

      final ProtocolException height = assertThrows(ProtocolException.class, () -> this.applyARegionOfAnotherHeight(canvas));
      final boolean namesTheOtherSize = height.getMessage().contains("4x2");
      assertTrue(namesTheOtherSize);
    }
  }

  private void applyARegionOfAnotherHeight(final FrameCanvas canvas) throws ProtocolException {
    canvas.apply(new FrameRegion(4, 2, 0, 0, 1, 1, new byte[4]));
  }

  @Test
  void shouldIgnoreRegionsAndHaveNoPictureOnceClosed() throws ProtocolException {
    final FrameCanvas canvas = new FrameCanvas(2, 2);
    canvas.close();
    canvas.close();
    canvas.apply(new FrameRegion(2, 2, 0, 0, 1, 1, new byte[4]));
    final @Nullable ImageBuffer picture = canvas.snapshot();
    assertNull(picture);
  }

  @Test
  void shouldPlaceEveryRowOfARegionOnItsOwnRow() throws ProtocolException {
    try (final FrameCanvas canvas = new FrameCanvas(4, 3)) {
      final byte[] pixels = new byte[2 * 2 * 4];
      System.arraycopy(this.bgra(2, 1, 10, REGION_GREEN, REGION_RED), 0, pixels, 0, 8);
      System.arraycopy(this.bgra(2, 1, 40, 50, 60), 0, pixels, 8, 8);
      canvas.apply(new FrameRegion(4, 3, 1, 1, 2, 2, pixels));
      try (final ImageBuffer image = this.snapshot(canvas)) {
        final int firstRowBlue = this.bgr(image, 1, 1)[0];
        assertEquals(10, firstRowBlue);

        final int secondRowBlue = this.bgr(image, 1, 2)[0];
        assertEquals(40, secondRowBlue);

        final int secondRowRed = this.bgr(image, 2, 2)[2];
        assertEquals(60, secondRowRed);
      }
    }
  }

  @Test
  void shouldReleaseTheNativePictureOnClose() {
    final FrameCanvas canvas = new FrameCanvas(4, 3);
    final boolean releasedWhileOpen = canvas.getPage().isNull();
    assertFalse(releasedWhileOpen);

    canvas.close();
    final boolean releasedAfterClose = canvas.getPage().isNull();
    assertTrue(releasedAfterClose);
  }
}
