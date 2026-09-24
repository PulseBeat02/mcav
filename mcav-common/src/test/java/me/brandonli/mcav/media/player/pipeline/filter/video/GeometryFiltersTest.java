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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.testing.Images;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

/**
 * Tests the filters that change the geometry of frames: crop, resize, rotation, transposition and flipping.
 */
final class GeometryFiltersTest {

  private static int[] indices(final ImageBuffer image) {
    final int[] pixels = image.getPixels();
    final int[] indices = new int[pixels.length];
    for (int index = 0; index < pixels.length; index++) {
      indices[index] = pixels[index] & 0xFFFFFF;
    }
    return indices;
  }

  private static boolean crop(final int x, final int y, final int width, final int height) {
    try (final ImageBuffer image = Images.indexed(4, 4)) {
      final CropFilter filter = new CropFilter(x, y, width, height);
      return filter.applyFilter(image);
    }
  }

  @Test
  void cropsToTheRequestedRegion() {
    try (final ImageBuffer image = Images.indexed(4, 4)) {
      final CropFilter filter = new CropFilter(1, 1, 2, 2);
      final boolean modified = filter.applyFilter(image);
      final int width = image.getWidth();
      final int[] indices = indices(image);
      assertTrue(modified);
      assertEquals(2, width);
      assertArrayEquals(new int[] { 5, 6, 9, 10 }, indices);
    }
  }

  @Test
  void clampsCropsThatReachBeyondTheFrame() {
    try (final ImageBuffer image = Images.indexed(4, 4)) {
      final CropFilter filter = new CropFilter(2, 2, 10, 10);
      filter.applyFilter(image);
      final int[] indices = indices(image);
      assertArrayEquals(new int[] { 10, 11, 14, 15 }, indices);
    }
  }

  @Test
  void leavesTheFrameAloneWhenTheCropIsEmptyOrCoversEverything() {
    final boolean rightOfFrame = crop(4, 0, 1, 1);
    final boolean belowFrame = crop(0, 4, 1, 1);
    final boolean wholeFrame = crop(0, 0, 4, 4);
    final boolean clampedToWholeFrame = crop(0, 0, 10, 10);
    final boolean shiftedRight = crop(1, 0, 3, 4);
    final boolean shiftedDown = crop(0, 1, 4, 3);
    final boolean narrower = crop(0, 0, 2, 4);
    final boolean shorter = crop(0, 0, 4, 2);
    assertFalse(rightOfFrame);
    assertFalse(belowFrame);
    assertFalse(wholeFrame);
    assertFalse(clampedToWholeFrame);
    assertTrue(shiftedRight);
    assertTrue(shiftedDown);
    assertTrue(narrower);
    assertTrue(shorter);
  }

  @Test
  void cropRejectsInvalidRegions() {
    final CropFilter filter = new CropFilter(0, 0, 1, 1);
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(-1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, -1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, 0, 1, 0));
    try (final Mat mat = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(mat));
    }
  }

  @Test
  void resizesUpAndDown() {
    try (final ImageBuffer image = Images.indexed(4, 4)) {
      final ResizeFilter same = new ResizeFilter(4, 4);
      final ResizeFilter shorter = new ResizeFilter(4, 2);
      final ResizeFilter narrower = new ResizeFilter(2, 2);
      final ResizeFilter larger = new ResizeFilter(8, 6);
      final boolean sameModified = same.applyFilter(image);
      final boolean shorterModified = shorter.applyFilter(image);
      final int shorterHeight = image.getHeight();
      final boolean narrowerModified = narrower.applyFilter(image);
      final int narrowerWidth = image.getWidth();
      final boolean largerModified = larger.applyFilter(image);
      final int largerWidth = image.getWidth();
      final int largerHeight = image.getHeight();
      final int[] largerPixels = image.getPixels();
      final int pixelCount = largerPixels.length;
      assertFalse(sameModified);
      assertTrue(shorterModified);
      assertEquals(2, shorterHeight);
      assertTrue(narrowerModified);
      assertEquals(2, narrowerWidth);
      assertTrue(largerModified);
      assertEquals(8, largerWidth);
      assertEquals(6, largerHeight);
      assertEquals(48, pixelCount);
    }
  }

  @Test
  void shrinkingEitherAxisAveragesAllThreeSourcePixels() {
    // Three source pixels [0, 90, 0] average to 30. Linear center sampling
    // would instead choose 90, so dimensions alone cannot establish correctness.
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 3 : 1;
      final int height = horizontal ? 1 : 3;
      try (final ImageBuffer image = Images.solid(width, height, 0xFF000000)) {
        image.setPixel(horizontal ? 1 : 0, horizontal ? 0 : 1, new double[] { 90.0, 90.0, 90.0 });
        final ResizeFilter filter = new ResizeFilter(1, 1);
        final boolean modified = filter.applyFilter(image);
        final int[] actual = image.getPixels();
        assertTrue(modified);
        assertArrayEquals(new int[] { 0xFF1E1E1E }, actual);
      }
    }
  }

  @Test
  void growingEitherAxisInterpolatesBetweenPixelCenters() {
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 2 : 1;
      final int height = horizontal ? 1 : 2;
      try (final ImageBuffer image = Images.solid(width, height, 0xFF000000)) {
        image.setPixel(horizontal ? 1 : 0, horizontal ? 0 : 1, new double[] { 80.0, 80.0, 80.0 });
        final ResizeFilter filter = new ResizeFilter(horizontal ? 4 : 1, horizontal ? 1 : 4);
        final boolean modified = filter.applyFilter(image);
        final int[] actual = image.getPixels();
        assertTrue(modified);
        // Source coordinates -0.25, 0.25, 0.75, 1.25 with edge clamping.
        assertArrayEquals(new int[] { 0xFF000000, 0xFF141414, 0xFF3C3C3C, 0xFF505050 }, actual);
      }
    }
  }

  @Test
  void resizeKnowsItsTargetSize() {
    final ResizeFilter filter = new ResizeFilter(8, 6);
    final boolean exact = filter.matches(8, 6);
    final boolean otherHeight = filter.matches(8, 7);
    final boolean otherWidth = filter.matches(7, 6);
    assertTrue(exact);
    assertFalse(otherHeight);
    assertFalse(otherWidth);
    try (final Mat mat = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(mat));
    }
    assertThrows(IllegalArgumentException.class, () -> new ResizeFilter(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ResizeFilter(1, 0));
  }

  @Test
  void rotatesInEveryDirection() {
    try (
      final ImageBuffer clockwise = Images.indexed(3, 2);
      final ImageBuffer halfTurn = Images.indexed(3, 2);
      final ImageBuffer counterClockwise = Images.indexed(3, 2)
    ) {
      final RotationFilter clockwiseFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_90_CLOCKWISE);
      final RotationFilter halfTurnFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
      final RotationFilter counterClockwiseFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE);
      final boolean clockwiseModified = clockwiseFilter.applyFilter(clockwise);
      final boolean halfTurnModified = halfTurnFilter.applyFilter(halfTurn);
      final boolean counterClockwiseModified = counterClockwiseFilter.applyFilter(counterClockwise);
      assertTrue(clockwiseModified, "the filter reports the change, which decides whether the result is written back");
      assertTrue(halfTurnModified);
      assertTrue(counterClockwiseModified);
      final int clockwiseWidth = clockwise.getWidth();
      final int[] clockwiseIndices = indices(clockwise);
      final int[] halfTurnIndices = indices(halfTurn);
      final int[] counterClockwiseIndices = indices(counterClockwise);
      assertEquals(2, clockwiseWidth);
      assertArrayEquals(new int[] { 3, 0, 4, 1, 5, 2 }, clockwiseIndices);
      assertArrayEquals(new int[] { 5, 4, 3, 2, 1, 0 }, halfTurnIndices);
      assertArrayEquals(new int[] { 2, 5, 1, 4, 0, 3 }, counterClockwiseIndices);
    }
  }

  @Test
  void rotationUsesTheOpenCvCodes() {
    final int clockwise = RotationFilter.Rotation.ROTATE_90_CLOCKWISE.getCode();
    final int halfTurn = RotationFilter.Rotation.ROTATE_180.getCode();
    final int counterClockwise = RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE.getCode();
    final RotationFilter filter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
    assertEquals(opencv_core.ROTATE_90_CLOCKWISE, clockwise);
    assertEquals(opencv_core.ROTATE_180, halfTurn);
    assertEquals(opencv_core.ROTATE_90_COUNTERCLOCKWISE, counterClockwise);
    try (final Mat mat = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(mat));
    }
    assertThrows(NullPointerException.class, () -> new RotationFilter(null));
  }

  @Test
  void transposesRowsAndColumns() {
    try (final ImageBuffer image = Images.indexed(3, 2)) {
      final TransposeFilter filter = new TransposeFilter();
      final boolean modified = filter.applyFilter(image);
      final int width = image.getWidth();
      final int[] indices = indices(image);
      assertTrue(modified);
      assertEquals(2, width);
      assertArrayEquals(new int[] { 0, 3, 1, 4, 2, 5 }, indices);
    }
    try (final Mat mat = new Mat(1, 1, opencv_core.CV_8UC3)) {
      final TransposeFilter filter = new TransposeFilter();
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(mat));
    }
  }

  @Test
  void flipsInEveryDirection() {
    try (
      final ImageBuffer horizontal = Images.indexed(2, 2);
      final ImageBuffer vertical = Images.indexed(2, 2);
      final ImageBuffer both = Images.indexed(2, 2)
    ) {
      final FlipFilter horizontalFilter = new FlipFilter(FlipFilter.FlipDirection.HORIZONTAL);
      final FlipFilter verticalFilter = new FlipFilter(FlipFilter.FlipDirection.VERTICAL);
      final FlipFilter bothFilter = new FlipFilter(FlipFilter.FlipDirection.BOTH);
      final boolean horizontalModified = horizontalFilter.applyFilter(horizontal);
      final boolean verticalModified = verticalFilter.applyFilter(vertical);
      final boolean bothModified = bothFilter.applyFilter(both);
      assertTrue(horizontalModified, "the filter reports the change, which decides whether the result is written back");
      assertTrue(verticalModified);
      assertTrue(bothModified);
      final int[] horizontalIndices = indices(horizontal);
      final int[] verticalIndices = indices(vertical);
      final int[] bothIndices = indices(both);
      final int horizontalCode = FlipFilter.FlipDirection.HORIZONTAL.getCode();
      final int verticalCode = FlipFilter.FlipDirection.VERTICAL.getCode();
      final int bothCode = FlipFilter.FlipDirection.BOTH.getCode();
      assertArrayEquals(new int[] { 1, 0, 3, 2 }, horizontalIndices);
      assertArrayEquals(new int[] { 2, 3, 0, 1 }, verticalIndices);
      assertArrayEquals(new int[] { 3, 2, 1, 0 }, bothIndices);
      assertEquals(1, horizontalCode);
      assertEquals(0, verticalCode);
      assertEquals(-1, bothCode);
    }
    assertThrows(NullPointerException.class, () -> new FlipFilter(null));
  }

  @Test
  void resizingEveryFrameAllocatesNoMatrixOnceTheSizesAreSettled() {
    try (final ImageBuffer image = Images.indexed(4, 4)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final ResizeFilter filter = new ResizeFilter(2, 2);
      final int[] frame = Images.indexedPixels(4, 4);
      filter.applyFilter(image);
      final Mat firstResult = buffer.getMat();
      final int[] firstPixels = image.getPixels();
      image.updateArgb(frame, 4, 4);
      final Mat firstFrame = buffer.getMat();
      filter.applyFilter(image);
      final Mat secondResult = buffer.getMat();
      final int[] secondPixels = image.getPixels();
      image.updateArgb(frame, 4, 4);
      final Mat secondFrame = buffer.getMat();
      assertSame(firstResult, secondResult, "the result goes into the matrix of the previous result");
      assertSame(firstFrame, secondFrame, "and the player refills the frame into the matrix of the previous frame");
      assertArrayEquals(firstPixels, secondPixels);
    }
  }

  @Test
  void sizePreservingRotationsSwapBetweenTwoMatrices() {
    try (final ImageBuffer image = Images.indexed(2, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final RotationFilter filter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
      final Mat original = buffer.getMat();
      filter.applyFilter(image);
      filter.applyFilter(image);
      final Mat afterTwoTurns = buffer.getMat();
      final int[] indices = indices(image);
      assertSame(original, afterTwoTurns, "two half turns end up in the original matrix without allocating");
      assertArrayEquals(new int[] { 0, 1, 2, 3 }, indices);
    }
  }

  @Test
  void geometryFiltersWorkOnMatBuffersDirectly() {
    try (final ImageBuffer image = Images.indexed(4, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final TransposeFilter filter = new TransposeFilter();
      final boolean modified = filter.modifyImage(buffer);
      final int width = image.getWidth();
      assertTrue(modified);
      assertEquals(2, width);
    }
  }
}
