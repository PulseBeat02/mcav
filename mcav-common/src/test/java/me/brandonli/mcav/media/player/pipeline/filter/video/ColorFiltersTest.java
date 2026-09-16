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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.testing.Images;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

/**
 * Tests the filters that change the colors of frames.
 */
final class ColorFiltersTest {

  private static final double[] BLUE = { 255.0, 0.0, 0.0 };

  private static int pixelAt(final ImageBuffer image, final int x, final int y) {
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    return pixels[y * width + x];
  }

  private static int applyToSolid(final VideoFilter filter, final int argb) {
    try (final ImageBuffer image = Images.solid(3, 3, argb)) {
      final boolean modified = filter.applyFilter(image);
      assertTrue(modified);
      return pixelAt(image, 1, 1);
    }
  }

  @Test
  void grayscaleMakesAllChannelsEqual() {
    final int gray = applyToSolid(new GrayscaleFilter(), 0xFF102030);
    final int red = (gray >> 16) & 0xFF;
    final int green = (gray >> 8) & 0xFF;
    final int blue = gray & 0xFF;
    assertEquals(red, green);
    assertEquals(green, blue);
    assertEquals(0xFF1D1D1D, gray, "0.299 * 0x10 + 0.587 * 0x20 + 0.114 * 0x30 = 29, with red read from the third byte");
  }

  @Test
  void invertFlipsEveryChannel() {
    final int inverted = applyToSolid(new InvertFilter(), 0xFF102030);
    assertEquals(0xFFEFDFCF, inverted);
  }

  @Test
  void luminanceScalesAndShiftsEveryChannel() {
    final int adjusted = applyToSolid(new LuminanceFilter(2.0, 10.0), 0xFF102030);
    assertEquals(0xFF2A4A6A, adjusted);
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(-1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(1.0, 300.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(1.0, -256.0));
  }

  @Test
  void thresholdKeepsOnlyBrightChannels() {
    final ThresholdFilter filter = new ThresholdFilter(127.0, 255.0, opencv_imgproc.THRESH_BINARY);
    final int thresholded = applyToSolid(filter, 0xFF80107F);
    assertEquals(0xFFFF0000, thresholded);
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(-1.0, 255.0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(256.0, 255.0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(0.0, -1.0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(0.0, 256.0, 0));
  }

  @Test
  void tintMixesTheColorIn() {
    final int tinted = applyToSolid(new TintFilter(BLUE, 0.5), 0xFF000000);
    final int red = (tinted >> 16) & 0xFF;
    final int blue = tinted & 0xFF;
    final boolean halfBlue = blue == 127 || blue == 128;
    final String hexadecimal = Integer.toHexString(tinted);
    assertEquals(0, red);
    assertTrue(halfBlue, hexadecimal);
    assertThrows(IllegalArgumentException.class, () -> new TintFilter(BLUE, -0.1));
    assertThrows(IllegalArgumentException.class, () -> new TintFilter(BLUE, 1.1));
  }

  /**
   * Tints a black frame of the given size and returns the blue channel of its first pixel.
   */
  private static int tintedBlue(final TintFilter filter, final int width, final int height) {
    try (final ImageBuffer image = Images.solid(width, height, 0xFF000000)) {
      filter.applyFilter(image);
      final int tinted = pixelAt(image, 0, 0);
      return tinted & 0xFF;
    }
  }

  private static boolean isHalf(final int level) {
    return level == 127 || level == 128;
  }

  @Test
  void tintRebuildsItsColorOnlyForFramesOfAnotherSizeOrType() {
    final TintFilter filter = new TintFilter(BLUE, 0.5);
    final int first = tintedBlue(filter, 3, 3);
    final int sameSize = tintedBlue(filter, 3, 3);
    final int otherWidth = tintedBlue(filter, 2, 3);
    final int otherHeight = tintedBlue(filter, 2, 2);
    final int grayLevel;
    try (final Mat gray = new Mat(2, 2, opencv_core.CV_8UC1, new Scalar(0.0))) {
      filter.modifyMat(gray);
      final ByteBuffer levels = gray.createBuffer();
      grayLevel = levels.get(0) & 0xFF;
    }
    final int afterGray = tintedBlue(filter, 2, 2);
    assertTrue(isHalf(first), "first " + first);
    assertTrue(isHalf(sameSize), "the kept color is reused " + sameSize);
    assertTrue(isHalf(otherWidth), "another width " + otherWidth);
    assertTrue(isHalf(otherHeight), "another height " + otherHeight);
    assertTrue(isHalf(grayLevel), "a single channel is tinted with the first component " + grayLevel);
    assertTrue(isHalf(afterGray), "and the next color frame gets three channels again " + afterGray);
  }

  @Test
  void acceptsTheBoundaryValuesOfEveryColorFilter() {
    final LuminanceFilter darkest = new LuminanceFilter(0.0, -255.0);
    final LuminanceFilter brightest = new LuminanceFilter(1.0, 255.0);
    final ThresholdFilter lowest = new ThresholdFilter(0.0, 0.0, opencv_imgproc.THRESH_BINARY);
    final ThresholdFilter highest = new ThresholdFilter(255.0, 255.0, opencv_imgproc.THRESH_BINARY);
    final TintFilter untinted = new TintFilter(BLUE, 0.0);
    final TintFilter solidTint = new TintFilter(BLUE, 1.0);
    final int dimmed = applyToSolid(darkest, 0xFF102030);
    final int lifted = applyToSolid(brightest, 0xFF102030);
    final int lowThreshold = applyToSolid(lowest, 0xFF102030);
    final int highThreshold = applyToSolid(highest, 0xFF102030);
    final int keptColor = applyToSolid(untinted, 0xFF102030);
    final int fullTint = applyToSolid(solidTint, 0xFF102030);
    assertEquals(0xFF000000, dimmed, "a contrast of 0 and the lowest brightness are allowed and leave black");
    assertEquals(0xFFFFFFFF, lifted, "the highest brightness is allowed and saturates every channel");
    assertEquals(0xFF000000, lowThreshold, "a threshold and a max value of 0 are allowed");
    assertEquals(0xFF000000, highThreshold, "a threshold of 255 is allowed and lets no channel pass");
    assertEquals(0xFF102030, keptColor, "a strength of 0 is allowed and keeps the frame");
    assertEquals(0xFF0000FF, fullTint, "a strength of 1 is allowed and replaces the frame with the tint color");
  }

  @Test
  void blendAcceptsTheHighestWeight() {
    try (final ImageBuffer white = Images.solid(3, 3, 0xFFFFFFFF)) {
      final BlendFilter filter = new BlendFilter(white, 1.0);
      final int blended = applyToSolid(filter, 0xFF000000);
      assertEquals(0xFF000000, blended, "an alpha of 1 is allowed and keeps the frame");
    }
  }

  @Test
  void grayscaleReusesItsMatrixAcrossFrameSizes() {
    final GrayscaleFilter filter = new GrayscaleFilter();
    final int small = applyToSolid(filter, 0xFF102030);
    try (final ImageBuffer large = Images.solid(5, 4, 0xFF102030)) {
      filter.applyFilter(large);
      final int largeGray = pixelAt(large, 4, 3);
      assertEquals(0xFF1D1D1D, small);
      assertEquals(0xFF1D1D1D, largeGray);
    }
  }

  @Test
  void bilateralFiltersBareMatricesInPlace() {
    final BilateralFilter filter = new BilateralFilter(5, 50.0, 50.0);
    try (final Mat mat = new Mat(3, 3, opencv_core.CV_8UC3, new Scalar(0x60, 0x50, 0x40, 0.0))) {
      final boolean modified = filter.modifyMat(mat);
      final ByteBuffer pixels = mat.createBuffer();
      final int blue = pixels.get(12) & 0xFF;
      final int red = pixels.get(14) & 0xFF;
      assertTrue(modified);
      assertEquals(0x60, blue, "flat areas stay flat");
      assertEquals(0x40, red);
    }
  }

  @Test
  void colorMapRecolorsEveryPixelByItsBrightness() {
    final ColorMapFilter filter = new ColorMapFilter(opencv_imgproc.COLORMAP_JET);
    final int fromGray = applyToSolid(filter, 0xFF1D1D1D);
    final int fromColor = applyToSolid(filter, 0xFF102030);
    final int fromMidGray = applyToSolid(filter, 0xFF808080);
    assertEquals(fromGray, fromColor, "0x102030 has the luma 0x1D of the gray, so both get the same color");
    assertNotEquals(fromGray, fromMidGray, "other brightness levels get other colors");
    assertNotEquals(0xFF808080, fromMidGray, "gray levels are replaced by colors");
  }

  @Test
  void blendMixesTwoFramesOfTheSameSize() {
    try (final ImageBuffer white = Images.solid(3, 3, 0xFFFFFFFF)) {
      final BlendFilter filter = new BlendFilter(white, 0.25);
      final int blended = applyToSolid(filter, 0xFF000000);
      assertEquals(0xFFBFBFBF, blended);
    }
  }

  @Test
  void blendIgnoresFramesOfAnotherSize() {
    try (final ImageBuffer other = Images.solid(2, 2, 0xFFFFFFFF); final ImageBuffer image = Images.solid(3, 3, 0xFF000000)) {
      final BlendFilter filter = new BlendFilter(other, 0.5);
      final boolean modified = filter.applyFilter(image);
      assertFalse(modified);
    }
    try (final ImageBuffer tall = Images.solid(3, 4, 0xFFFFFFFF); final ImageBuffer image = Images.solid(3, 3, 0xFF000000)) {
      final BlendFilter filter = new BlendFilter(tall, 0.5);
      final boolean modified = filter.applyFilter(image);
      assertFalse(modified);
    }
  }

  @Test
  void blendAcceptsImagesThatAreNotBackedByAMat() {
    final ImageBuffer other = mock(ImageBuffer.class);
    when(other.getWidth()).thenReturn(3);
    when(other.getHeight()).thenReturn(3);
    when(other.getPixels()).thenReturn(
      new int[] { 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF }
    );
    final BlendFilter filter = new BlendFilter(other, 0.0);
    final int blended = applyToSolid(filter, 0xFF000000);
    assertEquals(0xFFFFFFFF, blended);
    assertThrows(IllegalArgumentException.class, () -> new BlendFilter(other, -0.1));
    assertThrows(IllegalArgumentException.class, () -> new BlendFilter(other, 1.1));
    assertThrows(NullPointerException.class, () -> new BlendFilter(null, 0.5));
  }

  @Test
  void bilateralKeepsFlatAreasFlat() {
    final int filtered = applyToSolid(new BilateralFilter(5, 50.0, 50.0), 0xFF405060);
    assertEquals(0xFF405060, filtered);
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(0, 1.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, 0.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, 1.0, 0.0));
  }

  @Test
  void everySmoothingBlurSpreadsASinglePixelToItsNeighbors() {
    final BlurFilter.BlurType[] smoothing = { BlurFilter.BlurType.NORMAL, BlurFilter.BlurType.GAUSSIAN, BlurFilter.BlurType.STACK };
    for (final BlurFilter.BlurType type : smoothing) {
      try (final ImageBuffer image = Images.solid(5, 5, 0xFF000000)) {
        image.setPixel(2, 2, new double[] { 255.0, 255.0, 255.0 });
        final BlurFilter filter = new BlurFilter(type, 3, 1.0, 1.0);
        final boolean modified = filter.applyFilter(image);
        final int center = pixelAt(image, 2, 2);
        final int neighbor = pixelAt(image, 1, 2);
        final int neighborBlue = neighbor & 0xFF;
        final String name = type.name();
        assertTrue(modified, name);
        assertNotEquals(0xFFFFFFFF, center, name + " darkens the bright pixel");
        assertTrue(neighborBlue > 0, name + " brightens its neighbors");
      }
    }
  }

  @Test
  void medianBlurRemovesASinglePixel() {
    try (final ImageBuffer image = Images.solid(5, 5, 0xFF000000)) {
      image.setPixel(2, 2, new double[] { 255.0, 255.0, 255.0 });
      final BlurFilter filter = new BlurFilter(BlurFilter.BlurType.MEDIAN, 3);
      final boolean modified = filter.applyFilter(image);
      final int center = pixelAt(image, 2, 2);
      final int neighbor = pixelAt(image, 1, 2);
      assertTrue(modified);
      assertEquals(0xFF000000, center, "the median of eight black pixels and one white pixel is black");
      assertEquals(0xFF000000, neighbor, "the median does not spread the pixel");
    }
  }

  @Test
  void blurRejectsInvalidKernels() {
    final BlurFilter simple = new BlurFilter(BlurFilter.BlurType.NORMAL, 3);
    final int blurred = applyToSolid(simple, 0xFF102030);
    assertEquals(0xFF102030, blurred);
    final BlurFilter evenBox = new BlurFilter(BlurFilter.BlurType.NORMAL, 2);
    final int evenBlurred = applyToSolid(evenBox, 0xFF102030);
    assertEquals(0xFF102030, evenBlurred, "the box blur accepts even kernels, as OpenCV does");
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.NORMAL, 0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.MEDIAN, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.STACK, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, 3, -1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, 3, 0.0, -1.0));
    assertThrows(NullPointerException.class, () -> new BlurFilter(null, 3));
  }

  @Test
  void dilationGrowsBrightAreas() {
    try (final ImageBuffer image = Images.solid(5, 5, 0xFF000000)) {
      image.setPixel(2, 2, new double[] { 255.0, 255.0, 255.0 });
      final DilationFilter filter = new DilationFilter(3);
      final boolean modified = filter.applyFilter(image);
      final int neighbor = pixelAt(image, 1, 1);
      final int corner = pixelAt(image, 0, 0);
      assertTrue(modified, "the filter reports the change, which decides whether the result is written back");
      assertEquals(0xFFFFFFFF, neighbor);
      assertEquals(0xFF000000, corner);
    }
    assertThrows(IllegalArgumentException.class, () -> new DilationFilter(0));
  }

  @Test
  void erosionGrowsDarkAreas() {
    try (final ImageBuffer image = Images.solid(5, 5, 0xFFFFFFFF)) {
      image.setPixel(2, 2, new double[] { 0.0, 0.0, 0.0 });
      final ErosionFilter filter = new ErosionFilter(3);
      final boolean modified = filter.applyFilter(image);
      final int neighbor = pixelAt(image, 1, 1);
      final int corner = pixelAt(image, 0, 0);
      assertTrue(modified, "the filter reports the change, which decides whether the result is written back");
      assertEquals(0xFF000000, neighbor);
      assertEquals(0xFFFFFFFF, corner);
    }
    assertThrows(IllegalArgumentException.class, () -> new ErosionFilter(0));
  }
}
