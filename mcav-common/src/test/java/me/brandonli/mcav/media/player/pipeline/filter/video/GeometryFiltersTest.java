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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import me.brandonli.mcav.testing.Images;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.junit.jupiter.api.Test;

final class GeometryFiltersTest {

  private static final int SQUARE_SIDE = 4;
  private static final int SHRUNK_SIDE = 2;

  private static final int SMALL_SQUARE_SIDE = 2;

  private static final int RECTANGLE_WIDTH = 3;
  private static final int RECTANGLE_HEIGHT = 2;

  private static final int ENLARGED_WIDTH = 8;
  private static final int ENLARGED_HEIGHT = 6;

  private static final int BLACK = 0xFF000000;

  private static final double SCALE_FROM_SIZE = 0.0;

  private static final String CHANGE_REPORTED_MESSAGE = "the filter reports the change, which decides whether the result is written back";

  private static int[] indices(final ImageBuffer image) {
    final int[] pixels = image.getPixels();
    final int[] indices = new int[pixels.length];
    for (int index = 0; index < pixels.length; index++) {
      indices[index] = pixels[index] & 0xFFFFFF;
    }

    return indices;
  }

  private static boolean crop(final int left, final int top, final int width, final int height) {
    try (final ImageBuffer image = Images.indexed(SQUARE_SIDE, SQUARE_SIDE)) {
      final CropFilter filter = new CropFilter(left, top, width, height);
      final ByteBuffer before = ByteBuffer.allocate(image.getData().remaining()).put(image.getData()).flip();
      final boolean changed = filter.applyFilter(image);
      if (!changed) {
        final ByteBuffer imageData = image.getData();
        assertEquals(before, imageData);

        final int imageWidth = image.getWidth();
        assertEquals(SQUARE_SIDE, imageWidth);

        final int imageHeight = image.getHeight();
        assertEquals(SQUARE_SIDE, imageHeight);
      }

      return changed;
    }
  }

  @Test
  void shouldCropToTheRequestedRegion() {
    try (final ImageBuffer image = Images.indexed(SQUARE_SIDE, SQUARE_SIDE)) {
      final CropFilter filter = new CropFilter(1, 1, 2, 2);
      final boolean modified = filter.applyFilter(image);
      final int width = image.getWidth();
      final int[] indices = indices(image);
      final int[] expected = new int[] { 5, 6, 9, 10 };
      assertTrue(modified);
      assertEquals(2, width);
      assertArrayEquals(expected, indices);
    }
  }

  @Test
  void shouldClampCropsThatReachBeyondTheFrame() {
    try (final ImageBuffer image = Images.indexed(SQUARE_SIDE, SQUARE_SIDE)) {
      final CropFilter filter = new CropFilter(2, 2, 10, 10);
      filter.applyFilter(image);
      final int[] indices = indices(image);
      final int[] expected = new int[] { 10, 11, 14, 15 };
      assertArrayEquals(expected, indices);
    }
  }

  @Test
  void shouldLeaveTheFrameAloneWhenTheCropIsEmptyOrCoversEverything() {
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
  void shouldRejectInvalidCropRegions() {
    final CropFilter filter = new CropFilter(0, 0, 1, 1);
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(-1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, -1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new CropFilter(0, 0, 1, 0));

    try (final Mat matrix = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(matrix));
    }
  }

  @Test
  void shouldResizeUpAndDown() {
    try (final ImageBuffer image = Images.indexed(SQUARE_SIDE, SQUARE_SIDE)) {
      final ResizeFilter same = new ResizeFilter(SQUARE_SIDE, SQUARE_SIDE);
      final ResizeFilter shorter = new ResizeFilter(SQUARE_SIDE, SHRUNK_SIDE);
      final ResizeFilter narrower = new ResizeFilter(SHRUNK_SIDE, SHRUNK_SIDE);
      final ResizeFilter larger = new ResizeFilter(ENLARGED_WIDTH, ENLARGED_HEIGHT);
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
      assertEquals(SHRUNK_SIDE, shorterHeight);
      assertTrue(narrowerModified);
      assertEquals(SHRUNK_SIDE, narrowerWidth);
      assertTrue(largerModified);
      assertEquals(ENLARGED_WIDTH, largerWidth);
      assertEquals(ENLARGED_HEIGHT, largerHeight);
      assertEquals(48, pixelCount);
    }
  }

  @Test
  void shouldAverageAllThreeSourcePixelsWhenShrinkingEitherAxis() {
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 3 : 1;
      final int height = horizontal ? 1 : 3;
      try (final ImageBuffer image = Images.solid(width, height, BLACK)) {
        image.setPixel(horizontal ? 1 : 0, horizontal ? 0 : 1, new double[] { 90.0, 90.0, 90.0 });
        final ResizeFilter filter = new ResizeFilter(1, 1);
        final boolean modified = filter.applyFilter(image);
        final int[] actual = image.getPixels();
        final int[] expected = new int[] { 0xFF1E1E1E };
        assertTrue(modified);
        assertArrayEquals(expected, actual);
      }
    }
  }

  @Test
  void shouldInterpolateBetweenPixelCentersWhenGrowingEitherAxis() {
    for (final boolean horizontal : new boolean[] { true, false }) {
      final int width = horizontal ? 2 : 1;
      final int height = horizontal ? 1 : 2;
      try (final ImageBuffer image = Images.solid(width, height, BLACK)) {
        image.setPixel(horizontal ? 1 : 0, horizontal ? 0 : 1, new double[] { 80.0, 80.0, 80.0 });
        final ResizeFilter filter = new ResizeFilter(horizontal ? 4 : 1, horizontal ? 1 : 4);
        final boolean modified = filter.applyFilter(image);
        final int[] actual = image.getPixels();
        final int[] expected = new int[] { 0xFF000000, 0xFF141414, 0xFF3C3C3C, 0xFF505050 };
        assertTrue(modified);
        assertArrayEquals(expected, actual);
      }
    }
  }

  @Test
  void shouldAverageAreasWhenOneAxisShrinksAndTheOtherGrows() {
    try (
      final ImageBuffer image = Images.solid(3, 1, BLACK);
      final Size target = new Size(1, 2);
      final Mat area = new Mat();
      final Mat bilinear = new Mat()
    ) {
      image.setPixel(1, 0, new double[] { 90.0, 90.0, 90.0 });
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat source = buffer.getMat();
      opencv_imgproc.resize(source, area, target, SCALE_FROM_SIZE, SCALE_FROM_SIZE, opencv_imgproc.INTER_AREA);
      opencv_imgproc.resize(source, bilinear, target, SCALE_FROM_SIZE, SCALE_FROM_SIZE, opencv_imgproc.INTER_LINEAR);
      final ByteBuffer areaPixels = area.createBuffer();
      final ByteBuffer bilinearPixels = bilinear.createBuffer();
      assertNotEquals(bilinearPixels, areaPixels, "the frame tells the two interpolations apart");

      final ResizeFilter filter = new ResizeFilter(1, 2);
      final boolean modified = filter.applyFilter(image);
      final ByteBuffer pixels = image.getData();
      assertTrue(modified);
      assertEquals(areaPixels, pixels, "the width shrinks, so the areas are averaged although the height grows");
    }
  }

  @Test
  void shouldKnowTheTargetSizeOfAResize() {
    final ResizeFilter filter = new ResizeFilter(ENLARGED_WIDTH, ENLARGED_HEIGHT);
    final boolean exact = filter.matches(ENLARGED_WIDTH, ENLARGED_HEIGHT);
    final boolean otherHeight = filter.matches(ENLARGED_WIDTH, 7);
    final boolean otherWidth = filter.matches(7, ENLARGED_HEIGHT);
    assertTrue(exact);
    assertFalse(otherHeight);
    assertFalse(otherWidth);

    try (final Mat matrix = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(matrix));
    }

    assertThrows(IllegalArgumentException.class, () -> new ResizeFilter(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ResizeFilter(1, 0));
  }

  @Test
  void shouldRotateInEveryDirection() {
    try (
      final ImageBuffer clockwise = Images.indexed(RECTANGLE_WIDTH, RECTANGLE_HEIGHT);
      final ImageBuffer halfTurn = Images.indexed(RECTANGLE_WIDTH, RECTANGLE_HEIGHT);
      final ImageBuffer counterClockwise = Images.indexed(RECTANGLE_WIDTH, RECTANGLE_HEIGHT)
    ) {
      final RotationFilter clockwiseFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_90_CLOCKWISE);
      final RotationFilter halfTurnFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
      final RotationFilter counterClockwiseFilter = new RotationFilter(RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE);
      final boolean clockwiseModified = clockwiseFilter.applyFilter(clockwise);
      final boolean halfTurnModified = halfTurnFilter.applyFilter(halfTurn);
      final boolean counterClockwiseModified = counterClockwiseFilter.applyFilter(counterClockwise);
      assertTrue(clockwiseModified, CHANGE_REPORTED_MESSAGE);
      assertTrue(halfTurnModified);
      assertTrue(counterClockwiseModified);
      final int clockwiseWidth = clockwise.getWidth();
      final int[] clockwiseIndices = indices(clockwise);
      final int[] halfTurnIndices = indices(halfTurn);
      final int[] counterClockwiseIndices = indices(counterClockwise);
      final int[] expectedClockwiseIndices = new int[] { 3, 0, 4, 1, 5, 2 };
      final int[] expectedHalfTurnIndices = new int[] { 5, 4, 3, 2, 1, 0 };
      final int[] expectedCounterClockwiseIndices = new int[] { 2, 5, 1, 4, 0, 3 };
      assertEquals(2, clockwiseWidth);
      assertArrayEquals(expectedClockwiseIndices, clockwiseIndices);
      assertArrayEquals(expectedHalfTurnIndices, halfTurnIndices);
      assertArrayEquals(expectedCounterClockwiseIndices, counterClockwiseIndices);
    }
  }

  @Test
  void shouldUseTheOpenCvCodesForRotation() {
    final int clockwise = RotationFilter.Rotation.ROTATE_90_CLOCKWISE.getCode();
    final int halfTurn = RotationFilter.Rotation.ROTATE_180.getCode();
    final int counterClockwise = RotationFilter.Rotation.ROTATE_90_COUNTERCLOCKWISE.getCode();
    final RotationFilter filter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
    assertEquals(opencv_core.ROTATE_90_CLOCKWISE, clockwise);
    assertEquals(opencv_core.ROTATE_180, halfTurn);
    assertEquals(opencv_core.ROTATE_90_COUNTERCLOCKWISE, counterClockwise);

    try (final Mat matrix = new Mat(1, 1, opencv_core.CV_8UC3)) {
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(matrix));
    }

    assertThrows(NullPointerException.class, () -> new RotationFilter(null));
  }

  @Test
  void shouldTransposeRowsAndColumns() {
    try (final ImageBuffer image = Images.indexed(RECTANGLE_WIDTH, RECTANGLE_HEIGHT)) {
      final TransposeFilter filter = new TransposeFilter();
      final boolean modified = filter.applyFilter(image);
      final int width = image.getWidth();
      final int[] indices = indices(image);
      final int[] expected = new int[] { 0, 3, 1, 4, 2, 5 };
      assertTrue(modified);
      assertEquals(2, width);
      assertArrayEquals(expected, indices);
    }

    try (final Mat matrix = new Mat(1, 1, opencv_core.CV_8UC3)) {
      final TransposeFilter filter = new TransposeFilter();
      assertThrows(UnsupportedOperationException.class, () -> filter.modifyMat(matrix));
    }
  }

  @Test
  void shouldFlipInEveryDirection() {
    try (
      final ImageBuffer horizontal = Images.indexed(SMALL_SQUARE_SIDE, SMALL_SQUARE_SIDE);
      final ImageBuffer vertical = Images.indexed(SMALL_SQUARE_SIDE, SMALL_SQUARE_SIDE);
      final ImageBuffer both = Images.indexed(SMALL_SQUARE_SIDE, SMALL_SQUARE_SIDE)
    ) {
      final FlipFilter horizontalFilter = new FlipFilter(FlipFilter.FlipDirection.HORIZONTAL);
      final FlipFilter verticalFilter = new FlipFilter(FlipFilter.FlipDirection.VERTICAL);
      final FlipFilter bothFilter = new FlipFilter(FlipFilter.FlipDirection.BOTH);
      final boolean horizontalModified = horizontalFilter.applyFilter(horizontal);
      final boolean verticalModified = verticalFilter.applyFilter(vertical);
      final boolean bothModified = bothFilter.applyFilter(both);
      assertTrue(horizontalModified, CHANGE_REPORTED_MESSAGE);
      assertTrue(verticalModified);
      assertTrue(bothModified);
      final int[] horizontalIndices = indices(horizontal);
      final int[] verticalIndices = indices(vertical);
      final int[] bothIndices = indices(both);
      final int horizontalCode = FlipFilter.FlipDirection.HORIZONTAL.getCode();
      final int verticalCode = FlipFilter.FlipDirection.VERTICAL.getCode();
      final int bothCode = FlipFilter.FlipDirection.BOTH.getCode();
      final int[] expectedHorizontalIndices = new int[] { 1, 0, 3, 2 };
      final int[] expectedVerticalIndices = new int[] { 2, 3, 0, 1 };
      final int[] expectedBothIndices = new int[] { 3, 2, 1, 0 };
      assertArrayEquals(expectedHorizontalIndices, horizontalIndices);
      assertArrayEquals(expectedVerticalIndices, verticalIndices);
      assertArrayEquals(expectedBothIndices, bothIndices);
      assertEquals(1, horizontalCode);
      assertEquals(0, verticalCode);
      assertEquals(-1, bothCode);
    }

    assertThrows(NullPointerException.class, () -> new FlipFilter(null));
  }

  @Test
  void shouldAllocateNoMatrixOnceTheSizesAreSettledWhenResizingEveryFrame() {
    try (final ImageBuffer image = Images.indexed(SQUARE_SIDE, SQUARE_SIDE)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final ResizeFilter filter = new ResizeFilter(SHRUNK_SIDE, SHRUNK_SIDE);
      final int[] frame = Images.indexedPixels(SQUARE_SIDE, SQUARE_SIDE);
      filter.applyFilter(image);
      final Mat firstResult = buffer.getMat();
      final int[] firstPixels = image.getPixels();
      image.updateArgb(frame, SQUARE_SIDE, SQUARE_SIDE);
      final Mat firstFrame = buffer.getMat();
      filter.applyFilter(image);
      final Mat secondResult = buffer.getMat();
      final int[] secondPixels = image.getPixels();
      image.updateArgb(frame, SQUARE_SIDE, SQUARE_SIDE);
      final Mat secondFrame = buffer.getMat();
      assertSame(firstResult, secondResult, "the result goes into the matrix of the previous result");
      assertSame(firstFrame, secondFrame, "and the player refills the frame into the matrix of the previous frame");
      assertArrayEquals(firstPixels, secondPixels);
    }
  }

  @Test
  void shouldSwapBetweenTwoMatricesForSizePreservingRotations() {
    try (final ImageBuffer image = Images.indexed(SMALL_SQUARE_SIDE, SMALL_SQUARE_SIDE)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final RotationFilter filter = new RotationFilter(RotationFilter.Rotation.ROTATE_180);
      final Mat original = buffer.getMat();
      filter.applyFilter(image);
      filter.applyFilter(image);
      final Mat afterTwoTurns = buffer.getMat();
      final int[] indices = indices(image);
      final int[] expected = new int[] { 0, 1, 2, 3 };
      assertSame(original, afterTwoTurns, "two half turns end up in the original matrix without allocating");
      assertArrayEquals(expected, indices);
    }
  }
}
