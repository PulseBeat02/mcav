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
package me.brandonli.mcav.utils.opencv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

final class ImageUtilsTest {

  private static final int OPAQUE_COLOR = 0xFF123456;

  private static final int VALID_ORIGINAL_SIDE = 2;

  private static double[] convert(final double[] components) {
    try (final Scalar scalar = ImageUtils.toScalar(components)) {
      final double first = scalar.get(0);
      final double second = scalar.get(1);
      final double third = scalar.get(2);
      final double fourth = scalar.get(3);
      return new double[] { first, second, third, fourth };
    }
  }

  @Test
  void shouldPadShortComponentArraysWithZeros() {
    final double[] components = { 1.0, 2.0, 3.0 };
    final double[] values = convert(components);
    final double[] expected = new double[] { 1.0, 2.0, 3.0, 0.0 };
    assertArrayEquals(expected, values);
  }

  @Test
  void shouldIgnoreComponentsBeyondTheFourth() {
    final double[] components = { 1.0, 2.0, 3.0, 4.0, 5.0 };
    final double[] values = convert(components);
    final double[] expected = new double[] { 1.0, 2.0, 3.0, 4.0 };
    assertArrayEquals(expected, values);
  }

  @Test
  void shouldResizePackedPixels() {
    final int[] pixels = new int[4 * 4];
    Arrays.fill(pixels, OPAQUE_COLOR);
    final int[] resized = ImageUtils.resizeIntArrayImage(pixels, 4, 4, 2, 2);
    final int[] expected = new int[4];
    Arrays.fill(expected, OPAQUE_COLOR);
    assertEquals(4, resized.length);
    assertArrayEquals(expected, resized);
  }

  private static void assertOriginalSizeRejected(final int[] pixels, final int width, final int height) {
    final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, width, height, 1, 1)
    );
    final String message = rejected.getMessage();
    assertEquals("Original size must be positive!", message);
  }

  private static void assertNewSizeRejected(final int[] pixels, final int width, final int height) {
    final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, VALID_ORIGINAL_SIDE, VALID_ORIGINAL_SIDE, width, height)
    );
    final String message = rejected.getMessage();
    assertEquals("New size must be positive!", message);
  }

  @Test
  void shouldRejectInvalidArguments() {
    final int[] pixels = new int[4];
    assertThrows(NullPointerException.class, () -> convert(null));
    assertThrows(NullPointerException.class, () -> ImageUtils.resizeIntArrayImage(null, VALID_ORIGINAL_SIDE, VALID_ORIGINAL_SIDE, 1, 1));
    assertOriginalSizeRejected(pixels, 0, VALID_ORIGINAL_SIDE);
    assertOriginalSizeRejected(pixels, VALID_ORIGINAL_SIDE, 0);
    assertOriginalSizeRejected(pixels, -1, VALID_ORIGINAL_SIDE);
    assertOriginalSizeRejected(pixels, VALID_ORIGINAL_SIDE, -1);
    assertNewSizeRejected(pixels, 0, 1);
    assertNewSizeRejected(pixels, 1, 0);
    assertNewSizeRejected(pixels, -1, 1);
    assertNewSizeRejected(pixels, 1, -1);

    final IllegalArgumentException mismatched = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, 3, 3, 1, 1)
    );
    final String mismatchedMessage = mismatched.getMessage();
    assertEquals("Pixel count does not match the original size!", mismatchedMessage);
  }

  @Test
  void shouldRejectAnOriginalSizeWhosePackedBgrSizeExceedsTheIntegerRange() {
    final int[] pixels = new int[0];
    assertThrows(IllegalArgumentException.class, () -> ImageUtils.resizeIntArrayImage(pixels, 65_536, 65_536, 1, 1));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ImageUtils.class);
  }

  @Test
  void shouldResizePackedPixelsKeepingTheirLayout() {
    final int red = 0xFFFF0000;
    final int blue = 0xFF0000FF;
    final int[] pixels = { red, red, blue, blue, red, red, blue, blue };
    final int[] resized = ImageUtils.resizeIntArrayImage(pixels, 4, 2, 2, 1);
    final int[] translucent = { 0x00123456 };
    final int[] enlarged = ImageUtils.resizeIntArrayImage(translucent, 1, 1, 2, 2);
    final int opaque = OPAQUE_COLOR;
    final int[] expectedResized = new int[] { red, blue };
    final int[] expectedEnlarged = new int[] { opaque, opaque, opaque, opaque };
    assertArrayEquals(expectedResized, resized, "the left and the right half keep their colors");
    assertArrayEquals(expectedEnlarged, enlarged, "the result is fully opaque");
  }
}
