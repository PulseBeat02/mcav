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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.testing.Images;
import org.junit.jupiter.api.Test;

/**
 * Tests the filters that draw shapes, text and other images onto frames.
 */
final class DrawingFiltersTest {

  private static final double[] WHITE = { 255.0, 255.0, 255.0 };
  private static final int BLACK_ARGB = 0xFF000000;
  private static final int WHITE_ARGB = 0xFFFFFFFF;

  private static int pixelAt(final ImageBuffer image, final int x, final int y) {
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    return pixels[y * width + x];
  }

  private static int countNonBlack(final ImageBuffer image) {
    final int[] pixels = image.getPixels();
    int count = 0;
    for (final int pixel : pixels) {
      if (pixel != BLACK_ARGB) {
        count++;
      }
    }
    return count;
  }

  @Test
  void drawsCircles() {
    try (final ImageBuffer image = Images.solid(11, 11, BLACK_ARGB)) {
      final CircleFilter filter = new CircleFilter(4, 6, 3, WHITE);
      final boolean modified = filter.applyFilter(image);
      final int right = pixelAt(image, 7, 6);
      final int left = pixelAt(image, 1, 6);
      final int top = pixelAt(image, 4, 3);
      final int center = pixelAt(image, 4, 6);
      final int farAway = pixelAt(image, 10, 0);
      assertTrue(modified);
      assertEquals(WHITE_ARGB, right);
      assertEquals(WHITE_ARGB, left);
      assertEquals(WHITE_ARGB, top);
      assertEquals(BLACK_ARGB, center, "only the outline is drawn");
      assertEquals(BLACK_ARGB, farAway);
    }
    assertThrows(IllegalArgumentException.class, () -> new CircleFilter(0, 0, -1, WHITE));
  }

  @Test
  void drawsACircleOfRadiusZeroAsItsCenter() {
    try (final ImageBuffer image = Images.solid(5, 5, BLACK_ARGB)) {
      final CircleFilter filter = new CircleFilter(2, 2, 0, WHITE);
      final boolean modified = filter.applyFilter(image);
      final int center = pixelAt(image, 2, 2);
      final int corner = pixelAt(image, 0, 0);
      assertTrue(modified);
      assertEquals(WHITE_ARGB, center, "OpenCV accepts a radius of 0 and draws the center");
      assertEquals(BLACK_ARGB, corner);
    }
  }

  @Test
  void drawsEllipses() {
    try (final ImageBuffer image = Images.solid(11, 11, BLACK_ARGB)) {
      final EllipseFilter filter = new EllipseFilter(5, 5, 4, 2, 0, 0, 360, WHITE);
      final boolean modified = filter.applyFilter(image);
      final int right = pixelAt(image, 9, 5);
      final int left = pixelAt(image, 1, 5);
      final int bottom = pixelAt(image, 5, 7);
      final int center = pixelAt(image, 5, 5);
      final int beyondTheShortAxis = pixelAt(image, 5, 1);
      assertTrue(modified);
      assertEquals(WHITE_ARGB, right, "the horizontal half axis is 4");
      assertEquals(WHITE_ARGB, left);
      assertEquals(WHITE_ARGB, bottom, "the vertical half axis is 2");
      assertEquals(BLACK_ARGB, center);
      assertEquals(BLACK_ARGB, beyondTheShortAxis);
    }
    assertThrows(IllegalArgumentException.class, () -> new EllipseFilter(0, 0, 0, 1, 0, 0, 360, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new EllipseFilter(0, 0, 1, 0, 0, 0, 360, WHITE));
  }

  @Test
  void drawsLines() {
    try (final ImageBuffer image = Images.solid(5, 3, BLACK_ARGB)) {
      final LineFilter filter = new LineFilter(0, 1, 4, 1, WHITE);
      final boolean modified = filter.applyFilter(image);
      final int start = pixelAt(image, 0, 1);
      final int end = pixelAt(image, 4, 1);
      final int above = pixelAt(image, 2, 0);
      assertTrue(modified, "the filter reports the change, which decides whether the result is written back");
      assertEquals(WHITE_ARGB, start);
      assertEquals(WHITE_ARGB, end);
      assertEquals(BLACK_ARGB, above);
    }
  }

  @Test
  void drawsRectangleOutlines() {
    try (final ImageBuffer image = Images.solid(6, 6, BLACK_ARGB)) {
      final RectangleFilter filter = new RectangleFilter(1, 1, 3, 3, WHITE);
      final boolean modified = filter.applyFilter(image);
      assertTrue(modified, "the filter reports the change, which decides whether the result is written back");
      final int corner = pixelAt(image, 1, 1);
      final int oppositeCorner = pixelAt(image, 3, 3);
      final int center = pixelAt(image, 2, 2);
      final int outside = pixelAt(image, 0, 0);
      final int rightOfRectangle = pixelAt(image, 4, 1);
      final int belowRectangle = pixelAt(image, 1, 4);
      assertEquals(WHITE_ARGB, corner);
      assertEquals(WHITE_ARGB, oppositeCorner, "a rectangle at 1 with a width of 3 ends at 3");
      assertEquals(BLACK_ARGB, center, "only the outline is drawn");
      assertEquals(BLACK_ARGB, outside);
      assertEquals(BLACK_ARGB, rightOfRectangle, "a width of 3 covers three columns");
      assertEquals(BLACK_ARGB, belowRectangle, "a height of 3 covers three rows");
    }
    assertThrows(IllegalArgumentException.class, () -> new RectangleFilter(0, 0, 0, 1, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new RectangleFilter(0, 0, 1, 0, WHITE));
  }

  @Test
  void drawsText() {
    final TextFilter filter = new TextFilter("MCAV", 2, 20, TextFilter.DEFAULT_FONT, 0.8, WHITE);
    try (final ImageBuffer image = Images.solid(80, 30, BLACK_ARGB)) {
      final boolean modified = filter.applyFilter(image);
      final int drawn = countNonBlack(image);
      assertTrue(modified);
      assertTrue(drawn > 0);
    }
    assertThrows(NullPointerException.class, () -> new TextFilter(null, 0, 0, TextFilter.DEFAULT_FONT, 1.0, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new TextFilter("x", 0, 0, TextFilter.DEFAULT_FONT, 0.0, WHITE));
  }

  @Test
  void drawsTheMeasuredFrameRate() throws InterruptedException {
    final FPSFilter filter = new FPSFilter();
    try (final ImageBuffer first = Images.solid(120, 40, BLACK_ARGB); final ImageBuffer second = Images.solid(120, 40, BLACK_ARGB)) {
      final boolean firstModified = filter.applyFilter(first);
      Thread.sleep(1_050);
      final boolean secondModified = filter.applyFilter(second);
      assertTrue(firstModified, "the filter reports the change, which decides whether the result is written back");
      assertTrue(secondModified);
      final int firstDrawn = countNonBlack(first);
      final int secondDrawn = countNonBlack(second);
      final int[] firstPixels = first.getPixels();
      final int[] secondPixels = second.getPixels();
      final boolean sameText = Arrays.equals(firstPixels, secondPixels);
      assertTrue(firstDrawn > 0);
      assertTrue(secondDrawn > 0);
      assertFalse(sameText, "after one second the text changes from 0 fps to 2 fps");
    }
  }

  @Test
  void fillsRegionsClampedToTheFrame() {
    try (final ImageBuffer image = Images.solid(3, 2, BLACK_ARGB)) {
      final RegionScalarFilter topLeft = new RegionScalarFilter(0, 0, 2, 1, WHITE);
      final RegionScalarFilter clamped = new RegionScalarFilter(2, 1, 5, 5, WHITE);
      final boolean topLeftModified = topLeft.applyFilter(image);
      final boolean clampedModified = clamped.applyFilter(image);
      assertTrue(topLeftModified, "a region inside the frame reports the change");
      assertTrue(clampedModified, "and so does a region that only reaches into the frame");
      final int first = pixelAt(image, 0, 0);
      final int second = pixelAt(image, 1, 0);
      final int third = pixelAt(image, 2, 0);
      final int clampedCorner = pixelAt(image, 2, 1);
      final int untouched = pixelAt(image, 0, 1);
      assertEquals(WHITE_ARGB, first);
      assertEquals(WHITE_ARGB, second);
      assertEquals(BLACK_ARGB, third);
      assertEquals(WHITE_ARGB, clampedCorner);
      assertEquals(BLACK_ARGB, untouched);
    }
  }

  @Test
  void regionsOutsideTheFrameChangeNothing() {
    try (final ImageBuffer image = Images.solid(3, 2, BLACK_ARGB)) {
      final RegionScalarFilter right = new RegionScalarFilter(3, 0, 1, 1, WHITE);
      final RegionScalarFilter below = new RegionScalarFilter(0, 2, 1, 1, WHITE);
      final boolean rightModified = right.applyFilter(image);
      final boolean belowModified = below.applyFilter(image);
      assertFalse(rightModified);
      assertFalse(belowModified);
    }
    assertThrows(IllegalArgumentException.class, () -> new RegionScalarFilter(-1, 0, 1, 1, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new RegionScalarFilter(0, -1, 1, 1, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new RegionScalarFilter(0, 0, 0, 1, WHITE));
    assertThrows(IllegalArgumentException.class, () -> new RegionScalarFilter(0, 0, 1, 0, WHITE));
  }

  @Test
  void overlaysImagesClampedToTheFrame() {
    try (final ImageBuffer overlay = Images.solid(2, 2, 0xFFFF0000); final ImageBuffer image = Images.solid(4, 4, 0xFF0000FF)) {
      final OverlayImageFilter filter = new OverlayImageFilter(overlay, 3, 3);
      final boolean modified = filter.applyFilter(image);
      final int covered = pixelAt(image, 3, 3);
      final int uncovered = pixelAt(image, 2, 3);
      assertTrue(modified);
      assertEquals(0xFFFF0000, covered);
      assertEquals(0xFF0000FF, uncovered);
    }
  }

  @Test
  void overlaysOutsideTheFrameChangeNothing() {
    try (final ImageBuffer overlay = Images.solid(2, 2, 0xFFFF0000); final ImageBuffer image = Images.solid(4, 4, 0xFF0000FF)) {
      final OverlayImageFilter right = new OverlayImageFilter(overlay, 4, 0);
      final OverlayImageFilter below = new OverlayImageFilter(overlay, 0, 4);
      final boolean rightModified = right.applyFilter(image);
      final boolean belowModified = below.applyFilter(image);
      assertFalse(rightModified);
      assertFalse(belowModified);
      assertThrows(IllegalArgumentException.class, () -> new OverlayImageFilter(overlay, -1, 0));
      assertThrows(IllegalArgumentException.class, () -> new OverlayImageFilter(overlay, 0, -1));
    }
    assertThrows(NullPointerException.class, () -> new OverlayImageFilter(null, 0, 0));
  }

  @Test
  void overlaysAcceptImagesThatAreNotBackedByAMat() {
    final ImageBuffer overlay = mock(ImageBuffer.class);
    when(overlay.getWidth()).thenReturn(1);
    when(overlay.getHeight()).thenReturn(1);
    when(overlay.getPixels()).thenReturn(new int[] { 0xFF00FF00 });
    try (final ImageBuffer image = Images.solid(2, 2, BLACK_ARGB)) {
      final OverlayImageFilter filter = new OverlayImageFilter(overlay, 1, 1);
      filter.applyFilter(image);
      final int covered = pixelAt(image, 1, 1);
      assertEquals(0xFF00FF00, covered);
    }
  }

  @Test
  void zeroFilterBlanksTheFrame() {
    try (final ImageBuffer image = Images.solid(2, 2, WHITE_ARGB)) {
      final ZeroFilter filter = new ZeroFilter();
      final boolean modified = filter.applyFilter(image);
      final int remaining = countNonBlack(image);
      assertTrue(modified, "the filter reports the change, which decides whether the result is written back");
      assertEquals(0, remaining);
    }
  }
}
