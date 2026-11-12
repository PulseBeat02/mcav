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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.testing.Images;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

final class ColorFiltersTest {

  private static final double[] BLUE = { 255.0, 0.0, 0.0 };
  private static final double[] WHITE_CHANNELS = { 255.0, 255.0, 255.0 };

  private static final int BLACK = 0xFF000000;
  private static final int WHITE = 0xFFFFFFFF;
  private static final int SAMPLE_COLOR = 0xFF102030;
  private static final int SAMPLE_GRAY = 0xFF1D1D1D;

  private static final int RED_SHIFT = 16;
  private static final int CHANNEL_MASK = 0xFF;

  private static final int LOWER_HALF_LEVEL = 127;
  private static final int UPPER_HALF_LEVEL = 128;

  private static final int SOLID_SIDE = 3;

  private static final int SPOT_SIDE = 5;
  private static final int SPOT_CENTER = 2;

  private static final int KERNEL_SIZE = 3;

  private static final int BILATERAL_DIAMETER = 5;
  private static final double BILATERAL_SIGMA = 50.0;
  private static final double WIDE_BILATERAL_SIGMA = 1.0e9;

  private static final double THRESHOLD_MAXIMUM_VALUE = 255.0;

  private static final double TINT_STRENGTH = 0.5;
  private static final double BLEND_ALPHA = 0.5;

  private static final double TOO_LOW_WEIGHT = -0.1;
  private static final double TOO_HIGH_WEIGHT = 1.1;

  private static final String CHANGE_REPORTED_MESSAGE = "the filter reports the change, which decides whether the result is written back";

  private static int pixelAt(final ImageBuffer image, final int column, final int row) {
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    return pixels[row * width + column];
  }

  private static int applyToSolid(final VideoFilter filter, final int argb) {
    try (final ImageBuffer image = Images.solid(SOLID_SIDE, SOLID_SIDE, argb)) {
      final boolean modified = filter.applyFilter(image);
      assertTrue(modified);

      return pixelAt(image, 1, 1);
    }
  }

  @Test
  void shouldMakeAllChannelsEqualWithGrayscale() {
    final int gray = applyToSolid(new GrayscaleFilter(), SAMPLE_COLOR);
    final int red = (gray >> RED_SHIFT) & CHANNEL_MASK;
    final int green = (gray >> 8) & CHANNEL_MASK;
    final int blue = gray & CHANNEL_MASK;
    assertEquals(red, green);
    assertEquals(green, blue);
    assertEquals(SAMPLE_GRAY, gray, "0.299 * 0x10 + 0.587 * 0x20 + 0.114 * 0x30 = 29, with red read from the third byte");
  }

  @Test
  void shouldFlipEveryChannelWhenInverting() {
    final int inverted = applyToSolid(new InvertFilter(), SAMPLE_COLOR);
    assertEquals(0xFFEFDFCF, inverted);
  }

  @Test
  void shouldScaleAndShiftEveryChannelWithLuminance() {
    final int adjusted = applyToSolid(new LuminanceFilter(2.0, 10.0), SAMPLE_COLOR);
    assertEquals(0xFF2A4A6A, adjusted);
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(-1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(1.0, 300.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(1.0, -256.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(Double.NaN, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new LuminanceFilter(1.0, Double.NaN));
  }

  @Test
  void shouldKeepOnlyBrightChannelsWithAThreshold() {
    final ThresholdFilter filter = new ThresholdFilter(127.0, THRESHOLD_MAXIMUM_VALUE, opencv_imgproc.THRESH_BINARY);
    final int thresholded = applyToSolid(filter, 0xFF80107F);
    assertEquals(0xFFFF0000, thresholded);
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(-1.0, THRESHOLD_MAXIMUM_VALUE, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(256.0, THRESHOLD_MAXIMUM_VALUE, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(0.0, -1.0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(0.0, 256.0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(Double.NaN, THRESHOLD_MAXIMUM_VALUE, 0));
    assertThrows(IllegalArgumentException.class, () -> new ThresholdFilter(0.0, Double.NaN, 0));
  }

  @Test
  void shouldMixTheColorInWhenTinting() {
    final int tinted = applyToSolid(new TintFilter(BLUE, TINT_STRENGTH), BLACK);
    final int red = (tinted >> RED_SHIFT) & CHANNEL_MASK;
    final int blue = tinted & CHANNEL_MASK;
    final boolean isHalfBlue = blue == LOWER_HALF_LEVEL || blue == UPPER_HALF_LEVEL;
    final String hexadecimal = Integer.toHexString(tinted);
    assertEquals(0, red);
    assertTrue(isHalfBlue, hexadecimal);
    assertThrows(IllegalArgumentException.class, () -> new TintFilter(BLUE, TOO_LOW_WEIGHT));
    assertThrows(IllegalArgumentException.class, () -> new TintFilter(BLUE, TOO_HIGH_WEIGHT));
    assertThrows(IllegalArgumentException.class, () -> new TintFilter(BLUE, Double.NaN));
  }

  private static int tintedBlue(final TintFilter filter, final int width, final int height) {
    try (final ImageBuffer image = Images.solid(width, height, BLACK)) {
      filter.applyFilter(image);
      final int tinted = pixelAt(image, 0, 0);
      return tinted & CHANNEL_MASK;
    }
  }

  private static boolean isHalf(final int level) {
    return level == LOWER_HALF_LEVEL || level == UPPER_HALF_LEVEL;
  }

  @Test
  void shouldRebuildTheTintColorOnlyForFramesOfAnotherSizeOrType() throws ReflectiveOperationException {
    final TintFilter filter = new TintFilter(BLUE, TINT_STRENGTH);
    final int first = tintedBlue(filter, 3, 3);
    final Field holderField = TintFilter.class.getDeclaredField("tint");
    holderField.setAccessible(true);
    final Field idleField = ReusableMat.class.getDeclaredField("idle");
    idleField.setAccessible(true);
    final AtomicReference<?> idle = (AtomicReference<?>) idleField.get(holderField.get(filter));
    final Object kept = idle.get();
    final int sameSize = tintedBlue(filter, 3, 3);
    final Object reused = idle.get();
    assertSame(kept, reused, "a stable frame size reuses the cached tint matrix");

    final int otherWidth = tintedBlue(filter, 2, 3);
    final int otherHeight = tintedBlue(filter, 2, 2);
    final int grayLevel;
    try (final Mat gray = new Mat(2, 2, opencv_core.CV_8UC1, new Scalar(0.0))) {
      filter.modifyMat(gray);
      final ByteBuffer levels = gray.createBuffer();
      grayLevel = levels.get(0) & CHANNEL_MASK;
    }

    final int afterGray = tintedBlue(filter, 2, 2);
    final boolean firstHalf = isHalf(first);
    assertTrue(firstHalf, "first %s".formatted(first));

    final boolean sameSizeHalf = isHalf(sameSize);
    assertTrue(sameSizeHalf, "the kept color is reused %s".formatted(sameSize));

    final boolean otherWidthHalf = isHalf(otherWidth);
    assertTrue(otherWidthHalf, "another width %s".formatted(otherWidth));

    final boolean otherHeightHalf = isHalf(otherHeight);
    assertTrue(otherHeightHalf, "another height %s".formatted(otherHeight));

    final boolean grayHalf = isHalf(grayLevel);
    assertTrue(grayHalf, "a single channel is tinted with the first component %s".formatted(grayLevel));

    final boolean afterGrayHalf = isHalf(afterGray);
    assertTrue(afterGrayHalf, "and the next color frame gets three channels again %s".formatted(afterGray));
  }

  @Test
  void shouldAcceptTheBoundaryValuesOfEveryColorFilter() {
    final LuminanceFilter darkest = new LuminanceFilter(0.0, -255.0);
    final LuminanceFilter brightest = new LuminanceFilter(1.0, 255.0);
    final ThresholdFilter lowest = new ThresholdFilter(0.0, 0.0, opencv_imgproc.THRESH_BINARY);
    final ThresholdFilter highest = new ThresholdFilter(255.0, THRESHOLD_MAXIMUM_VALUE, opencv_imgproc.THRESH_BINARY);
    final TintFilter untinted = new TintFilter(BLUE, 0.0);
    final TintFilter solidTint = new TintFilter(BLUE, 1.0);
    final int dimmed = applyToSolid(darkest, SAMPLE_COLOR);
    final int lifted = applyToSolid(brightest, SAMPLE_COLOR);
    final int lowThreshold = applyToSolid(lowest, SAMPLE_COLOR);
    final int highThreshold = applyToSolid(highest, SAMPLE_COLOR);
    final int keptColor = applyToSolid(untinted, SAMPLE_COLOR);
    final int fullTint = applyToSolid(solidTint, SAMPLE_COLOR);
    assertEquals(BLACK, dimmed, "a contrast of 0 and the lowest brightness are allowed and leave black");
    assertEquals(WHITE, lifted, "the highest brightness is allowed and saturates every channel");
    assertEquals(BLACK, lowThreshold, "a threshold and a max value of 0 are allowed");
    assertEquals(BLACK, highThreshold, "a threshold of 255 is allowed and lets no channel pass");
    assertEquals(SAMPLE_COLOR, keptColor, "a strength of 0 is allowed and keeps the frame");
    assertEquals(0xFF0000FF, fullTint, "a strength of 1 is allowed and replaces the frame with the tint color");
  }

  @Test
  void shouldBlendWithTheHighestWeight() {
    try (final ImageBuffer white = Images.solid(SOLID_SIDE, SOLID_SIDE, WHITE)) {
      final BlendFilter filter = new BlendFilter(white, 1.0);
      final int blended = applyToSolid(filter, BLACK);
      assertEquals(BLACK, blended, "an alpha of 1 is allowed and keeps the frame");
    }
  }

  @Test
  void shouldReuseTheGrayscaleMatrixAcrossFrameSizes() {
    final GrayscaleFilter filter = new GrayscaleFilter();
    final int small = applyToSolid(filter, SAMPLE_COLOR);
    try (final ImageBuffer large = Images.solid(5, 4, SAMPLE_COLOR)) {
      filter.applyFilter(large);
      final int largeGray = pixelAt(large, 4, 3);
      assertEquals(SAMPLE_GRAY, small);
      assertEquals(SAMPLE_GRAY, largeGray);
    }
  }

  @Test
  void shouldFilterBareMatricesInPlaceWithBilateral() {
    final BilateralFilter filter = new BilateralFilter(BILATERAL_DIAMETER, BILATERAL_SIGMA, BILATERAL_SIGMA);
    try (final Mat matrix = new Mat(3, 3, opencv_core.CV_8UC3, new Scalar(0x60, 0x50, 0x40, 0.0))) {
      final boolean modified = filter.modifyMat(matrix);
      final ByteBuffer pixels = matrix.createBuffer();
      final int blue = pixels.get(12) & CHANNEL_MASK;
      final int red = pixels.get(14) & CHANNEL_MASK;
      assertTrue(modified);
      assertEquals(0x60, blue, "flat areas stay flat");
      assertEquals(0x40, red);
    }
  }

  @Test
  void shouldAverageANonuniformCrossInTheImageBufferWithBilateral() {
    final BilateralFilter filter = new BilateralFilter(KERNEL_SIZE, WIDE_BILATERAL_SIGMA, WIDE_BILATERAL_SIGMA);
    try (final ImageBuffer image = Images.solid(SPOT_SIDE, SPOT_SIDE, BLACK)) {
      image.setPixel(SPOT_CENTER, SPOT_CENTER, new double[] { 0.0, 0.0, 100.0 });
      final boolean modified = filter.applyFilter(image);
      final int center = pixelAt(image, SPOT_CENTER, SPOT_CENTER);
      final int neighbor = pixelAt(image, 1, SPOT_CENTER);
      final int distant = pixelAt(image, 0, 0);
      assertTrue(modified);
      assertEquals(0xFF140000, center);
      assertEquals(0xFF140000, neighbor);
      assertEquals(BLACK, distant);
    }
  }

  @Test
  void shouldWriteTheSmoothedPixelsBackToABareMatrixWithBilateral() {
    final BilateralFilter filter = new BilateralFilter(KERNEL_SIZE, WIDE_BILATERAL_SIGMA, WIDE_BILATERAL_SIGMA);
    try (final Scalar black = new Scalar(0.0); final Mat matrix = new Mat(SPOT_SIDE, SPOT_SIDE, opencv_core.CV_8UC3, black)) {
      final ByteBuffer original = matrix.createBuffer();
      original.put(3 * 12 + 2, (byte) 100);
      final boolean modified = filter.modifyMat(matrix);
      final ByteBuffer pixels = matrix.createBuffer();
      assertTrue(modified);
      final int center = pixels.get(3 * 12 + 2) & CHANNEL_MASK;
      assertEquals(20, center);
      final int neighbor = pixels.get(3 * 11 + 2) & CHANNEL_MASK;
      assertEquals(20, neighbor);
      final int corner = pixels.get(2) & CHANNEL_MASK;
      assertEquals(0, corner);
    }
  }

  @Test
  void shouldRecolorEveryPixelByItsBrightnessWithAColorMap() {
    final ColorMapFilter filter = new ColorMapFilter(opencv_imgproc.COLORMAP_JET);
    final int fromGray = applyToSolid(filter, SAMPLE_GRAY);
    final int fromColor = applyToSolid(filter, SAMPLE_COLOR);
    final int fromMiddleGray = applyToSolid(filter, 0xFF808080);
    assertEquals(fromGray, fromColor, "0x102030 has the luma 0x1D of the gray, so both get the same color");
    assertNotEquals(fromGray, fromMiddleGray, "other brightness levels get other colors");
    assertNotEquals(0xFF808080, fromMiddleGray, "gray levels are replaced by colors");
  }

  @Test
  void shouldBlendTwoFramesOfTheSameSize() {
    try (final ImageBuffer white = Images.solid(SOLID_SIDE, SOLID_SIDE, WHITE)) {
      final BlendFilter filter = new BlendFilter(white, 0.25);
      final int blended = applyToSolid(filter, BLACK);
      assertEquals(0xFFBFBFBF, blended);
    }
  }

  @Test
  void shouldNotBlendFramesOfAnotherSize() {
    try (final ImageBuffer other = Images.solid(2, 2, WHITE); final ImageBuffer image = Images.solid(SOLID_SIDE, SOLID_SIDE, BLACK)) {
      final BlendFilter filter = new BlendFilter(other, BLEND_ALPHA);
      final ByteBuffer before = ByteBuffer.allocate(image.getData().remaining()).put(image.getData()).flip();
      final boolean modified = filter.applyFilter(image);
      assertFalse(modified);
      final ByteBuffer after = image.getData();
      assertEquals(before, after);
      final int width = image.getWidth();
      assertEquals(SOLID_SIDE, width);
      final int height = image.getHeight();
      assertEquals(SOLID_SIDE, height);
    }

    try (
      final ImageBuffer tall = Images.solid(SOLID_SIDE, 4, WHITE);
      final ImageBuffer image = Images.solid(SOLID_SIDE, SOLID_SIDE, BLACK)
    ) {
      final BlendFilter filter = new BlendFilter(tall, BLEND_ALPHA);
      final ByteBuffer before = ByteBuffer.allocate(image.getData().remaining()).put(image.getData()).flip();
      final boolean modified = filter.applyFilter(image);
      assertFalse(modified);
      final ByteBuffer after = image.getData();
      assertEquals(before, after);
      final int width = image.getWidth();
      assertEquals(SOLID_SIDE, width);
      final int height = image.getHeight();
      assertEquals(SOLID_SIDE, height);
    }
  }

  @Test
  void shouldBlendImagesThatAreNotBackedByAMat() {
    final ImageBuffer other = mock(ImageBuffer.class);
    when(other.getWidth()).thenReturn(SOLID_SIDE);
    when(other.getHeight()).thenReturn(SOLID_SIDE);
    when(other.getPixels()).thenReturn(new int[] {
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
      0xFFFFFFFF,
    });
    final BlendFilter filter = new BlendFilter(other, 0.0);
    final int blended = applyToSolid(filter, BLACK);
    assertEquals(WHITE, blended);
    assertThrows(IllegalArgumentException.class, () -> new BlendFilter(other, TOO_LOW_WEIGHT));
    assertThrows(IllegalArgumentException.class, () -> new BlendFilter(other, TOO_HIGH_WEIGHT));
    assertThrows(IllegalArgumentException.class, () -> new BlendFilter(other, Double.NaN));
    assertThrows(NullPointerException.class, () -> new BlendFilter(null, BLEND_ALPHA));
  }

  @Test
  void shouldKeepFlatAreasFlatWithBilateral() {
    final int filtered = applyToSolid(new BilateralFilter(BILATERAL_DIAMETER, BILATERAL_SIGMA, BILATERAL_SIGMA), 0xFF405060);
    assertEquals(0xFF405060, filtered);
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(0, 1.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, 0.0, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, 1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, Double.NaN, 1.0));
    assertThrows(IllegalArgumentException.class, () -> new BilateralFilter(1, 1.0, Double.NaN));
  }

  @Test
  void shouldSpreadASinglePixelToItsNeighborsWithEverySmoothingBlur() {
    final BlurFilter.BlurType[] smoothing = { BlurFilter.BlurType.NORMAL, BlurFilter.BlurType.GAUSSIAN, BlurFilter.BlurType.STACK };
    for (final BlurFilter.BlurType type : smoothing) {
      try (final ImageBuffer image = Images.solid(SPOT_SIDE, SPOT_SIDE, BLACK)) {
        image.setPixel(SPOT_CENTER, SPOT_CENTER, WHITE_CHANNELS);
        final BlurFilter filter = new BlurFilter(type, KERNEL_SIZE, 1.0, 1.0);
        final boolean modified = filter.applyFilter(image);
        final int center = pixelAt(image, SPOT_CENTER, SPOT_CENTER);
        final int neighbor = pixelAt(image, 1, SPOT_CENTER);
        final int neighborBlue = neighbor & CHANNEL_MASK;
        final String name = type.name();
        assertTrue(modified, name);
        assertNotEquals(WHITE, center, "%s darkens the bright pixel".formatted(name));

        final boolean brightened = neighborBlue > 0;
        assertTrue(brightened, "%s brightens its neighbors".formatted(name));
      }
    }
  }

  @Test
  void shouldRemoveASinglePixelWithAMedianBlur() {
    try (final ImageBuffer image = Images.solid(SPOT_SIDE, SPOT_SIDE, BLACK)) {
      image.setPixel(SPOT_CENTER, SPOT_CENTER, WHITE_CHANNELS);
      final BlurFilter filter = new BlurFilter(BlurFilter.BlurType.MEDIAN, KERNEL_SIZE);
      final boolean modified = filter.applyFilter(image);
      final int center = pixelAt(image, SPOT_CENTER, SPOT_CENTER);
      final int neighbor = pixelAt(image, 1, SPOT_CENTER);
      assertTrue(modified);
      assertEquals(BLACK, center, "the median of eight black pixels and one white pixel is black");
      assertEquals(BLACK, neighbor, "the median does not spread the pixel");
    }
  }

  @Test
  void shouldRejectInvalidBlurKernels() {
    final BlurFilter simple = new BlurFilter(BlurFilter.BlurType.NORMAL, KERNEL_SIZE);
    final int blurred = applyToSolid(simple, SAMPLE_COLOR);
    assertEquals(SAMPLE_COLOR, blurred);

    final BlurFilter evenBox = new BlurFilter(BlurFilter.BlurType.NORMAL, 2);
    final int evenBlurred = applyToSolid(evenBox, SAMPLE_COLOR);
    assertEquals(SAMPLE_COLOR, evenBlurred, "the box blur accepts even kernels, as OpenCV does");
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.NORMAL, 0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.MEDIAN, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.STACK, 2));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, KERNEL_SIZE, -1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, KERNEL_SIZE, 0.0, -1.0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, KERNEL_SIZE, Double.NaN, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new BlurFilter(BlurFilter.BlurType.GAUSSIAN, KERNEL_SIZE, 0.0, Double.NaN));
    assertThrows(NullPointerException.class, () -> new BlurFilter(null, KERNEL_SIZE));
  }

  @Test
  void shouldGrowBrightAreasWithDilation() {
    try (final ImageBuffer image = Images.solid(SPOT_SIDE, SPOT_SIDE, BLACK)) {
      image.setPixel(SPOT_CENTER, SPOT_CENTER, WHITE_CHANNELS);
      final DilationFilter filter = new DilationFilter(KERNEL_SIZE);
      final boolean modified = filter.applyFilter(image);
      final int neighbor = pixelAt(image, 1, 1);
      final int corner = pixelAt(image, 0, 0);
      assertTrue(modified, CHANGE_REPORTED_MESSAGE);
      assertEquals(WHITE, neighbor);
      assertEquals(BLACK, corner);
    }

    assertThrows(IllegalArgumentException.class, () -> new DilationFilter(0));
  }

  @Test
  void shouldGrowDarkAreasWithErosion() {
    try (final ImageBuffer image = Images.solid(SPOT_SIDE, SPOT_SIDE, WHITE)) {
      image.setPixel(SPOT_CENTER, SPOT_CENTER, new double[] { 0.0, 0.0, 0.0 });
      final ErosionFilter filter = new ErosionFilter(KERNEL_SIZE);
      final boolean modified = filter.applyFilter(image);
      final int neighbor = pixelAt(image, 1, 1);
      final int corner = pixelAt(image, 0, 0);
      assertTrue(modified, CHANGE_REPORTED_MESSAGE);
      assertEquals(BLACK, neighbor);
      assertEquals(WHITE, corner);
    }

    assertThrows(IllegalArgumentException.class, () -> new ErosionFilter(0));
  }
}
