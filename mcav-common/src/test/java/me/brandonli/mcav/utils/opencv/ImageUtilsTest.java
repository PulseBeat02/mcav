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

/**
 * Tests {@link ImageUtils}.
 */
final class ImageUtilsTest {

  /**
   * Converts the components into a scalar, reads its four components back, and frees the native scalar again.
   */
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
  void padsShortComponentArraysWithZeros() {
    final double[] components = { 1.0, 2.0, 3.0 };
    final double[] values = convert(components);
    assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 0.0 }, values);
  }

  @Test
  void ignoresComponentsBeyondTheFourth() {
    final double[] components = { 1.0, 2.0, 3.0, 4.0, 5.0 };
    final double[] values = convert(components);
    assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, values);
  }

  @Test
  void resizesPackedPixels() {
    final int[] pixels = new int[4 * 4];
    Arrays.fill(pixels, 0xFF123456);
    final int[] resized = ImageUtils.resizeIntArrayImage(pixels, 4, 4, 2, 2);
    final int[] expected = new int[4];
    Arrays.fill(expected, 0xFF123456);
    assertEquals(4, resized.length);
    assertArrayEquals(expected, resized);
  }

  /**
   * Asserts that an original size is rejected by its own precondition, rather than by a later check that happens to
   * fail as well.
   */
  private static void assertOriginalSizeRejected(final int[] pixels, final int width, final int height) {
    final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, width, height, 1, 1)
    );
    final String message = rejected.getMessage();
    assertEquals("Original size must be positive", message);
  }

  /**
   * Asserts that a new size is rejected by its own precondition.
   */
  private static void assertNewSizeRejected(final int[] pixels, final int width, final int height) {
    final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, 2, 2, width, height)
    );
    final String message = rejected.getMessage();
    assertEquals("New size must be positive", message);
  }

  @Test
  void rejectsInvalidArguments() {
    final int[] pixels = new int[4];
    assertThrows(NullPointerException.class, () -> convert(null));
    assertThrows(NullPointerException.class, () -> ImageUtils.resizeIntArrayImage(null, 2, 2, 1, 1));
    assertOriginalSizeRejected(pixels, 0, 2);
    assertOriginalSizeRejected(pixels, 2, 0);
    assertOriginalSizeRejected(pixels, -1, 2);
    assertOriginalSizeRejected(pixels, 2, -1);
    assertNewSizeRejected(pixels, 0, 1);
    assertNewSizeRejected(pixels, 1, 0);
    assertNewSizeRejected(pixels, -1, 1);
    assertNewSizeRejected(pixels, 1, -1);
    final IllegalArgumentException mismatched = assertThrows(IllegalArgumentException.class, () ->
      ImageUtils.resizeIntArrayImage(pixels, 3, 3, 1, 1)
    );
    final String mismatchedMessage = mismatched.getMessage();
    assertEquals("Pixel count does not match the original size", mismatchedMessage);
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ImageUtils.class);
  }

  @Test
  void resizesPackedPixelsKeepingTheirLayout() {
    final int red = 0xFFFF0000;
    final int blue = 0xFF0000FF;
    final int[] pixels = { red, red, blue, blue, red, red, blue, blue };
    final int[] resized = ImageUtils.resizeIntArrayImage(pixels, 4, 2, 2, 1);
    final int[] translucent = { 0x00123456 };
    final int[] enlarged = ImageUtils.resizeIntArrayImage(translucent, 1, 1, 2, 2);
    final int opaque = 0xFF123456;
    assertArrayEquals(new int[] { red, blue }, resized, "the left and the right half keep their colors");
    assertArrayEquals(new int[] { opaque, opaque, opaque, opaque }, enlarged, "the result is fully opaque");
  }
}
