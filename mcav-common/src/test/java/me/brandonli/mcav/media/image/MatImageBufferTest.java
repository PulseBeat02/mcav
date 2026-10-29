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

import static me.brandonli.mcav.media.ResourceAssertions.assertThrowsWhileOpening;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.Images;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.http.DownloadException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class MatImageBufferTest {

  private static final String EXR_FILE_NAME = "image.exr";
  private static final String TEXT_FILE_NAME = "text.png";
  private static final String SIXTEEN_BIT_FILE_NAME = "sixteen.png";
  private static final String HEADER_ONLY_FILE_NAME = "header-only.ppm";
  private static final String TOO_TALL_FILE_NAME = "too-tall.pbm";

  private static final String ENCODED_FORMAT = "png";

  private static final int SMALL_IMAGE_SIZE = 2;

  private static final int BLACK = 0xFF000000;
  private static final int WHITE = 0xFFFFFFFF;
  private static final int RED = 0xFFFF0000;
  private static final int GREEN = 0xFF00FF00;
  private static final int BLUE = 0xFF0000FF;

  private static final int FIRST_COLOR = 0xFF123456;
  private static final int SECOND_COLOR = 0xFFABCDEF;
  private static final int THIRD_COLOR = 0xFF789ABC;
  private static final int FOURTH_COLOR = 0xFF654321;

  private static final double CHANNEL_MAXIMUM = 255.0;

  @TempDir
  private Path directory;

  private static int argbAt(final ImageBuffer image, final int column, final int row) {
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    return pixels[row * width + column];
  }

  @Test
  void shouldKeepPackedPixelsAndStoreThemAsBgr() {
    final int[] pixels = { 0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC };
    try (final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2)) {
      final int[] read = image.getPixels();
      final int width = image.getWidth();
      final int height = image.getHeight();
      final int count = image.getPixelCount();
      final ByteBuffer data = image.getData();
      final double[] pixel = image.getPixel(1, 0);
      assertArrayEquals(pixels, read);
      assertEquals(2, width);
      assertEquals(2, height);
      assertEquals(4, count);

      final int blue = data.get(0) & 0xFF;
      assertEquals(0x33, blue, "blue comes first");

      final int green = data.get(1) & 0xFF;
      assertEquals(0x22, green);

      final int red = data.get(2) & 0xFF;
      assertEquals(0x11, red);
      assertArrayEquals(new double[] { 0x66, 0x55, 0x44 }, pixel);
    }
  }

  @Test
  void shouldReadRawBgrBytes() {
    final byte[] bytes = { 1, 2, 3, 4, 5, 6 };
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    try (final ImageBuffer fromArray = ImageBuffer.bytes(bytes, 2, 1); final ImageBuffer fromBuffer = ImageBuffer.bytes(buffer, 2, 1)) {
      final int first = argbAt(fromArray, 0, 0);
      final int[] arrayPixels = fromArray.getPixels();
      final int[] bufferPixels = fromBuffer.getPixels();
      final int position = buffer.position();
      assertEquals(0xFF030201, first);
      assertArrayEquals(arrayPixels, bufferPixels);
      assertEquals(0, position, "the buffer must not be consumed");
    }

    final byte[] tallBytes = { 1, 2, 3, 4, 5, 6 };
    try (final ImageBuffer tall = ImageBuffer.bytes(tallBytes, 1, 2)) {
      final int top = argbAt(tall, 0, 0);
      final int bottom = argbAt(tall, 0, 1);
      assertEquals(0xFF030201, top, "an image of one pixel per row needs width times height times three bytes");
      assertEquals(0xFF060504, bottom);
    }

    final ByteBuffer shortBuffer = ByteBuffer.allocate(5);
    final ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(new byte[5], 2, 1));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(shortBuffer, 2, 1));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(emptyBuffer, 0, 1));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(emptyBuffer, 1, 0));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(new byte[0], 0, 1));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(new byte[0], 1, 0));
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.bytes((byte[]) null, 1, 1));
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.bytes((ByteBuffer) null, 1, 1));
  }

  @Test
  void shouldDecodeEncodedImages() {
    final BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    source.setRGB(3, 3, 0xFF0000);
    final byte[] png = Images.encode(source, ENCODED_FORMAT);
    try (final ImageBuffer image = ImageBuffer.bytes(png)) {
      final int width = image.getWidth();
      final int red = argbAt(image, 3, 3);
      assertEquals(8, width);
      assertEquals(RED, red);
    }

    final IllegalArgumentException empty = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(new byte[0]));
    final String emptyMessage = empty.getMessage();
    assertEquals("Encoded image must not be empty!", emptyMessage);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(new byte[] { 1, 2, 3 }));
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.bytes(null));
  }

  @Test
  void shouldRefuseAnImageOfADisabledCodecAsUnsupportedRatherThanCrashing() throws IOException {
    // the bundled OpenCV is built without the OpenEXR codec, so imdecode/imread raise an OpenCV error for an EXR
    // image; it is untrusted input, so it must be refused as unsupported, not escape as an unchecked RuntimeException
    final byte[] exr = { 'v', '/', '1', 0x01 };
    final Path exrFile = this.directory.resolve(EXR_FILE_NAME);
    Files.write(exrFile, exr);
    final FileSource fromFile = FileSource.path(exrFile);

    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(exr));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.path(fromFile));
  }

  @Test
  void shouldLoadImagesFromFilesAndUris() throws IOException {
    final Path png = TestMedia.png();
    final FileSource file = FileSource.path(png);
    final Path notAnImage = this.directory.resolve(TEXT_FILE_NAME);
    Files.writeString(notAnImage, "not an image");
    final FileSource invalid = FileSource.path(notAnImage);
    try (final ImageBuffer fromFile = ImageBuffer.path(file)) {
      final int width = fromFile.getWidth();
      final int white = argbAt(fromFile, 0, 0);
      assertEquals(16, width);
      assertEquals(WHITE, white);
    }

    final int downloadedHeight = this.downloadHeight(png);
    assertEquals(8, downloadedHeight);
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.path(invalid));

    final Path missing = this.directory.resolve("missing.png");
    final FileSource missingSource = FileSource.path(missing);
    final String expected = "File %s is not a supported image!".formatted(missing);
    final String previous = System.getProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    System.clearProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    try {
      final IllegalArgumentException withLimit = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
        ImageBuffer.path(missingSource)
      );
      final String withLimitMessage = withLimit.getMessage();
      final Throwable cause = withLimit.getCause();
      assertEquals(expected, withLimitMessage);
      assertInstanceOf(NoSuchFileException.class, cause);

      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "0");
      final IllegalArgumentException withoutLimit = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
        ImageBuffer.path(missingSource)
      );
      final String withoutLimitMessage = withoutLimit.getMessage();
      assertEquals(expected, withoutLimitMessage);
    } finally {
      restoreMaximumPixels(previous);
    }

    this.assertDownloadFailure();
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.path(null));
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.uri(null));
  }

  @Test
  void shouldRefuseAnImageThatDeclaresMorePixelsThanTheLimitBeforeDecodingIt() throws IOException {
    final BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
    final byte[] png = Images.encode(source, ENCODED_FORMAT);
    final Path file = this.directory.resolve(SIXTEEN_BIT_FILE_NAME);
    Files.write(file, png);
    final FileSource path = FileSource.path(file);
    final String expected = "Image size 16x16 exceeds the limit of 255 pixels that the system property mcav.image.maxPixels sets!";
    final String previous = System.getProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "255");
    try {
      final IllegalArgumentException fromBytes = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(png));
      final String bytesMessage = fromBytes.getMessage();
      assertEquals(expected, bytesMessage);

      final IllegalArgumentException fromFile = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.path(path));
      final String fileMessage = fromFile.getMessage();
      assertEquals(expected, fileMessage);

      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "256");
      try (final ImageBuffer atTheLimit = ImageBuffer.bytes(png); final ImageBuffer fileAtTheLimit = ImageBuffer.path(path)) {
        final int width = atTheLimit.getWidth();
        final int height = fileAtTheLimit.getHeight();
        assertEquals(16, width);
        assertEquals(16, height);
      }
    } finally {
      restoreMaximumPixels(previous);
    }
  }

  @Test
  void shouldRefuseAHeaderThatDeclaresAHugeSizeForItsSize() {
    final byte[] header = new byte[] {
      (byte) 0x89,
      'P',
      'N',
      'G',
      '\r',
      '\n',
      0x1A,
      '\n',
      0,
      0,
      0,
      13,
      'I',
      'H',
      'D',
      'R',
      0,
      0,
      (byte) 0xFF,
      (byte) 0xFF,
      0,
      0,
      (byte) 0xFF,
      (byte) 0xFF,
      8,
      2,
      0,
      0,
      0,
    };
    final String previous = System.getProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    System.clearProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    try {
      final IllegalArgumentException refused = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(header));
      final String message = refused.getMessage();
      assertEquals(
        "Image size 65535x65535 exceeds the limit of 67108864 pixels that the system property mcav.image.maxPixels sets!",
        message
      );
    } finally {
      restoreMaximumPixels(previous);
    }
  }

  @Test
  void shouldRefuseAnImageWhosePictureIsMissingAfterAValidHeader() throws IOException {
    final byte[] headerOnly = "P6\n4 4\n255\n".getBytes(StandardCharsets.US_ASCII);
    final IllegalArgumentException fromBytes = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.bytes(headerOnly)
    );
    final String bytesMessage = fromBytes.getMessage();
    assertEquals("Bytes are not a supported image format!", bytesMessage);

    final Path file = this.directory.resolve(HEADER_ONLY_FILE_NAME);
    Files.write(file, headerOnly);
    final FileSource path = FileSource.path(file);
    final IllegalArgumentException fromFile = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.path(path));
    final String fileMessage = fromFile.getMessage();
    assertEquals("File " + file + " is not a supported image!", fileMessage);
  }

  @Test
  void shouldRefuseAnImageWithASideLongerThanOpenCvAllows() throws IOException {
    final byte[] tooTall = "P4\n16 2000000\n".getBytes(StandardCharsets.US_ASCII);
    final IllegalArgumentException fromBytes = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.bytes(tooTall));
    final String bytesMessage = fromBytes.getMessage();
    assertEquals("Bytes are not a supported image format!", bytesMessage);

    final Path file = this.directory.resolve(TOO_TALL_FILE_NAME);
    Files.write(file, tooTall);
    final FileSource path = FileSource.path(file);
    final IllegalArgumentException fromFile = assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.path(path));
    final String fileMessage = fromFile.getMessage();
    assertEquals("File " + file + " is not a supported image!", fileMessage);
  }

  private static void restoreMaximumPixels(final @Nullable String previous) {
    if (previous == null) {
      System.clearProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    } else {
      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, previous);
    }
  }

  // Serves an image from a local HTTP server and loads it through its URI, with the download cache in the temporary
  // directory instead of the real home directory.
  private int downloadHeight(final Path png) throws IOException {
    final String previousHome = System.getProperty("user.home");
    final String temporaryHome = this.directory.toString();
    System.setProperty("user.home", temporaryHome);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final byte[] content = Files.readAllBytes(png);
      server.respond("/image.png", 200, content);
      final URI uri = server.uri("/image.png");
      final UriSource source = UriSource.uri(uri);
      try (final ImageBuffer fromUri = ImageBuffer.uri(source)) {
        return fromUri.getHeight();
      }
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  private void assertDownloadFailure() {
    final String previousHome = System.getProperty("user.home");
    final String temporaryHome = this.directory.toString();
    System.setProperty("user.home", temporaryHome);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/missing.png", 404, new byte[0]);
      final URI uri = server.uri("/missing.png");
      final UriSource source = UriSource.uri(uri);
      assertThrowsWhileOpening(DownloadException.class, () -> ImageBuffer.uri(source));
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  @Test
  void shouldKeepContinuousBgrMatsAndCopyRegions() {
    final Mat bgr = new Mat(2, 2, opencv_core.CV_8UC3, new Scalar(1.0, 2.0, 3.0, 0.0));
    final Mat wide = new Mat(4, 4, opencv_core.CV_8UC3, new Scalar(9.0, 8.0, 7.0, 0.0));
    final Rect region = new Rect(1, 1, 2, 2);
    final Mat nonContinuous = new Mat(wide, region);
    try (final ImageBuffer kept = ImageBuffer.mat(bgr); final ImageBuffer fromRegion = ImageBuffer.mat(nonContinuous)) {
      final Mat keptMat = ((MatImageBuffer) kept).getMat();
      final int regionPixel = argbAt(fromRegion, 1, 1);
      final int regionWidth = fromRegion.getWidth();
      assertSame(bgr, keptMat);
      assertEquals(0xFF070809, regionPixel);
      assertEquals(2, regionWidth);
    }

    wide.release();
  }

  @Test
  void shouldConvertMatsOfOtherChannelCountsAndDepths() {
    final Scalar gray = new Scalar(200.0);
    final Scalar opaqueBlue = new Scalar(CHANNEL_MAXIMUM, 0.0, 0.0, CHANNEL_MAXIMUM);
    final Scalar deepRed = new Scalar(0.0, 0.0, 1000.0, 0.0);
    final Mat oneChannel = new Mat(2, 2, opencv_core.CV_8UC1, gray);
    final Mat fourChannels = new Mat(2, 2, opencv_core.CV_8UC4, opaqueBlue);
    final Mat sixteenBit = new Mat(2, 2, opencv_core.CV_16UC3, deepRed);
    try (
      final ImageBuffer fromGray = ImageBuffer.mat(oneChannel);
      final ImageBuffer fromFour = ImageBuffer.mat(fourChannels);
      final ImageBuffer fromSixteen = ImageBuffer.mat(sixteenBit)
    ) {
      final int grayPixel = argbAt(fromGray, 0, 0);
      final int bluePixel = argbAt(fromFour, 0, 0);
      final int redPixel = argbAt(fromSixteen, 0, 0);
      assertEquals(0xFFC8C8C8, grayPixel);
      assertEquals(BLUE, bluePixel);
      assertEquals(0xFF040000, redPixel, "16-bit values are scaled to eight bits, 1000 / 257 rounds to 4");
    }

    final boolean grayReleased = oneChannel.empty();
    final boolean fourReleased = fourChannels.empty();
    final boolean sixteenReleased = sixteenBit.empty();
    assertTrue(grayReleased, "a matrix that had to be converted is released");
    assertTrue(fourReleased);
    assertTrue(sixteenReleased);
  }

  @Test
  void shouldConvertSingleChannelMatsOfOtherDepthsToColor() {
    final Scalar bright = new Scalar(25_700.0);
    final Mat sixteenBitGray = new Mat(2, 2, opencv_core.CV_16UC1, bright);
    try (final ImageBuffer fromSixteenBitGray = ImageBuffer.mat(sixteenBitGray)) {
      final int pixel = argbAt(fromSixteenBitGray, 1, 1);
      final ByteBuffer data = fromSixteenBitGray.getData();
      final int bytes = data.remaining();
      assertEquals(0xFF646464, pixel, "25700 of 65535 is 100 of 255, in every channel");
      assertEquals(2 * 2 * 3, bytes, "the image holds three channels per pixel");
    }
  }

  @Test
  void shouldRejectMatsItCannotConvert() {
    try (final Mat twoChannels = new Mat(2, 2, opencv_core.CV_8UC2); final Mat empty = new Mat()) {
      assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.mat(twoChannels));
      assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.mat(empty));
    }

    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.mat(null));
  }

  private static int convertedPixel(final int type, final double value) {
    final Scalar scalar = new Scalar(value, value, value, 0.0);
    final Mat mat = new Mat(1, 1, type, scalar);
    try (final ImageBuffer image = ImageBuffer.mat(mat)) {
      return argbAt(image, 0, 0);
    }
  }

  @Test
  void shouldMapTheFullRangeOfEveryDepthOntoEightBits() {
    final int unsignedSixteen = convertedPixel(opencv_core.CV_16UC3, 65_535.0);
    final int signedEightMiddle = convertedPixel(opencv_core.CV_8SC3, 0.0);
    final int signedEightLowest = convertedPixel(opencv_core.CV_8SC3, -128.0);
    final int signedSixteenMiddle = convertedPixel(opencv_core.CV_16SC3, 0.0);
    final int signedThirtyTwoMiddle = convertedPixel(opencv_core.CV_32SC3, 0.0);
    final int floatWhite = convertedPixel(opencv_core.CV_32FC3, 1.0);
    final int floatHalf = convertedPixel(opencv_core.CV_32FC3, 0.5);
    final int doubleQuarter = convertedPixel(opencv_core.CV_64FC3, 0.25);
    final int unsignedSixteenGray = convertedPixel(opencv_core.CV_16UC1, 65_535.0);
    assertEquals(WHITE, unsignedSixteenGray, "single channels of other depths are converted, then spread to BGR");
    assertEquals(WHITE, unsignedSixteen, "the largest 16-bit value is white");
    assertEquals(0xFF808080, signedEightMiddle, "signed values are centered on 128");
    assertEquals(BLACK, signedEightLowest);
    assertEquals(0xFF808080, signedSixteenMiddle);
    assertEquals(0xFF808080, signedThirtyTwoMiddle);
    assertEquals(WHITE, floatWhite, "floating point images range from 0 to 1");
    assertEquals(0xFF808080, floatHalf);
    assertEquals(0xFF404040, doubleQuarter);
  }

  @Test
  void shouldReleaseTheEightBitCopyOfAConvertedMat() {
    final Mat source = new Mat(1, 1, opencv_core.CV_16UC1);
    final List<Mat> released = new ArrayList<>();
    final Mat bgr = MatImageBuffer.copyToBgr(source, released::add);
    final int count = released.size();
    assertEquals(1, count);

    final Mat copy = released.getFirst();
    assertNotSame(source, copy, "the caller's matrix is not released");
    assertNotSame(bgr, copy, "the returned matrix is not released");

    copy.release();
    bgr.release();
    source.release();
  }

  @Test
  void shouldReleaseNothingOfAnEightBitMat() {
    final Mat source = new Mat(1, 1, opencv_core.CV_8UC1);
    final List<Mat> released = new ArrayList<>();
    final Mat bgr = MatImageBuffer.copyToBgr(source, released::add);
    assertEquals(List.of(), released, "an eight-bit matrix needs no copy, and the caller's matrix is not released");

    bgr.release();
    source.release();
  }

  @Test
  void shouldNoticeWhenTheMatPointsAtOtherPixels() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final int[] before = image.getPixels();
      final int blackPixel = before[0];
      final Mat own = buffer.getMat();
      final Scalar white = new Scalar(CHANNEL_MAXIMUM, CHANNEL_MAXIMUM, CHANNEL_MAXIMUM, 0.0);
      final Mat other = new Mat(2, 2, opencv_core.CV_8UC3, white);
      own.put(other);
      other.release();
      final int[] after = image.getPixels();
      final int whitePixel = after[0];
      assertEquals(BLACK, blackPixel);
      assertEquals(WHITE, whitePixel, "the cache follows the pixel data, not the matrix header");
    }
  }

  @Test
  void shouldReuseItsConversionArray() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final byte[] first = buffer.getScratch(12);
      final byte[] smaller = buffer.getScratch(6);
      image.invalidateCache();
      image.getPixels();
      final int[] pixels = { 1, 2, 3, 4 };
      image.updateArgb(pixels, 2, 2);
      final byte[] afterUse = buffer.getScratch(12);
      final byte[] larger = buffer.getScratch(24);
      assertSame(first, smaller, "a large enough array is reused");
      assertSame(first, afterUse, "reading and writing pixels reuse the array instead of allocating one per frame");
      assertEquals(24, larger.length, "the array grows when it is too small");
    }
  }

  @Test
  void shouldCopyTheImageOutOfFrames() {
    final Frame frame = new Frame(2, 1, Frame.DEPTH_UBYTE, 3);
    final ByteBuffer frameBytes = (ByteBuffer) frame.image[0];
    frameBytes.put(new byte[] { 10, 20, 30, 40, 50, 60 });
    try (final ImageBuffer image = ImageBuffer.frame(frame)) {
      frameBytes.put(0, (byte) 99);
      final int first = argbAt(image, 0, 0);
      assertEquals(0xFF1E140A, first, "later changes to the frame must not show");
    }

    try (final Frame bgra = new Frame(1, 1, Frame.DEPTH_UBYTE, 4)) {
      final ByteBuffer bgraBytes = (ByteBuffer) bgra.image[0];
      bgraBytes.put(new byte[] { 10, 20, 30, 40 });
      try (final ImageBuffer image = ImageBuffer.frame(bgra)) {
        final int pixel = argbAt(image, 0, 0);
        assertEquals(0xFF1E140A, pixel, "the fourth channel is alpha and is dropped");
      }
    }

    try (final Frame gray = new Frame(1, 1, Frame.DEPTH_UBYTE, 1)) {
      final ByteBuffer grayBytes = (ByteBuffer) gray.image[0];
      grayBytes.put(new byte[] { 77 });
      try (final ImageBuffer image = ImageBuffer.frame(gray)) {
        final int pixel = argbAt(image, 0, 0);
        assertEquals(0xFF4D4D4D, pixel, "one channel is gray");
      }
    }

    try (final Frame unsupported = new Frame(1, 1, Frame.DEPTH_UBYTE, 2)) {
      final IllegalArgumentException failure = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
        ImageBuffer.frame(unsupported)
      );
      final String message = failure.getMessage();
      assertEquals("Channel count 2 is not supported!", message);
    }

    try (final Frame audio = new Frame()) {
      assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.frame(audio));
    }

    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.frame(null));
  }

  @Test
  void shouldConvertBufferedImagesOfEveryType() {
    final int[] types = {
      BufferedImage.TYPE_3BYTE_BGR,
      BufferedImage.TYPE_INT_RGB,
      BufferedImage.TYPE_INT_ARGB,
      BufferedImage.TYPE_4BYTE_ABGR,
      BufferedImage.TYPE_BYTE_GRAY,
    };
    for (final int type : types) {
      final BufferedImage source = new BufferedImage(3, 2, type);
      source.setRGB(2, 1, WHITE);
      try (final ImageBuffer image = ImageBuffer.image(source)) {
        final int white = argbAt(image, 2, 1);
        final int black = argbAt(image, 0, 0);
        assertEquals(WHITE, white, "type " + type);
        assertEquals(BLACK, black, "type " + type);
      }
    }

    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.image(null));
  }

  @Test
  void shouldCopyPackedSrgbSamplesWithoutConvertingThem() {
    final ConversionCountingImage source = new ConversionCountingImage();
    try (final ImageBuffer _ = ImageBuffer.image(source)) {
      final int conversions = source.getConversions();
      assertEquals(0, conversions, "the samples of a packed sRGB image are copied as they are");
    }
  }

  @Test
  void shouldConvertTheSamplesOfALinearBgrImageToSrgb() {
    final ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
    final ComponentColorModel colorModel = new ComponentColorModel(linear, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
    final WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 1, 1, 3, 3, new int[] { 2, 1, 0 }, null);
    raster.setPixel(0, 0, new int[] { 8, 8, 8 });
    final BufferedImage source = new BufferedImage(colorModel, raster, false, null);
    final int type = source.getType();
    final int expected = source.getRGB(0, 0);
    try (final ImageBuffer image = ImageBuffer.image(source)) {
      final int pixel = argbAt(image, 0, 0);
      assertEquals(BufferedImage.TYPE_3BYTE_BGR, type, "a linear image reports the type of an sRGB one");
      assertEquals(expected, pixel, "the linear samples are converted to sRGB rather than copied");
    }
  }

  @Test
  void shouldConvertThePixelsOfALinearIntegerImageToSrgb() {
    final ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
    final DirectColorModel colorModel = new DirectColorModel(linear, 24, 0xFF0000, 0xFF00, 0xFF, 0, false, DataBuffer.TYPE_INT);
    final WritableRaster raster = colorModel.createCompatibleWritableRaster(1, 1);
    raster.setPixel(0, 0, new int[] { 8, 8, 8 });
    final BufferedImage source = new BufferedImage(colorModel, raster, false, null);
    final int type = source.getType();
    final int expected = source.getRGB(0, 0);
    try (final ImageBuffer image = ImageBuffer.image(source)) {
      final int pixel = argbAt(image, 0, 0);
      assertEquals(BufferedImage.TYPE_INT_RGB, type, "a linear image reports the type of an sRGB one");
      assertEquals(expected, pixel, "the linear pixels are converted to sRGB rather than copied");
    }
  }

  private static BufferedImage imageWithExtraStorage(final int type, final int offset) {
    final BufferedImage prototype = new BufferedImage(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, type);
    final SampleModel model = prototype.getSampleModel();
    final ColorModel colors = prototype.getColorModel();
    final int elements = type == BufferedImage.TYPE_INT_RGB ? 4 : 12;
    final int length = elements + offset + 2;
    final DataBuffer data;
    if (type == BufferedImage.TYPE_INT_RGB) {
      final int[] backing = new int[length];
      data = new DataBufferInt(backing, elements, offset);
    } else {
      final byte[] backing = new byte[length];
      data = new DataBufferByte(backing, elements, offset);
    }

    final Point origin = new Point();
    final WritableRaster raster = Raster.createWritableRaster(model, data, origin);
    final BufferedImage image = new BufferedImage(colors, raster, false, null);
    image.setRGB(0, 0, FIRST_COLOR);
    image.setRGB(1, 0, SECOND_COLOR);
    image.setRGB(0, 1, THIRD_COLOR);
    image.setRGB(1, 1, FOURTH_COLOR);
    return image;
  }

  private static void assertCopiesRasterPixels(final BufferedImage source) {
    final int width = source.getWidth();
    final int height = source.getHeight();
    final int[] expected = source.getRGB(0, 0, width, height, null, 0, width);
    try (final ImageBuffer converted = ImageBuffer.image(source); final ImageBuffer replaced = Images.indexed(width, height)) {
      final int[] convertedPixels = converted.getPixels();
      replaced.setAsBufferedImage(source);
      final int[] replacedPixels = replaced.getPixels();
      assertArrayEquals(expected, convertedPixels);
      assertArrayEquals(expected, replacedPixels);
    }
  }

  @Test
  void shouldCopyLogicalPixelsWhenTheBackingArrayHasExtraStorage() {
    final int[] types = { BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR };
    for (final int type : types) {
      for (int offset = 0; offset <= 1; offset++) {
        final BufferedImage source = imageWithExtraStorage(type, offset);
        final int actualType = source.getType();
        assertEquals(type, actualType, "the image is recognized as a standard packed type");
        assertCopiesRasterPixels(source);
      }
    }
  }

  @Test
  void shouldCopyIntegerRowsUsingTheirScanlineStride() {
    final BufferedImage prototype = new BufferedImage(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
    final ColorModel colors = prototype.getColorModel();
    final int[] masks = { 0xFF0000, 0xFF00, 0xFF };
    final SampleModel model = new SinglePixelPackedSampleModel(DataBuffer.TYPE_INT, 2, 2, 1, masks);
    final int[] pixels = { 0x123456, 0xABCDEF, 0x789ABC, 0x654321 };
    final DataBuffer data = new DataBufferInt(pixels, pixels.length);
    final Point origin = new Point();
    final WritableRaster raster = Raster.createWritableRaster(model, data, origin);
    final BufferedImage source = new BufferedImage(colors, raster, false, null);
    final int type = source.getType();
    assertEquals(BufferedImage.TYPE_INT_RGB, type);
    assertCopiesRasterPixels(source);
  }

  @Test
  void shouldCopyStandardImagesWithExactBackingStorage() {
    for (int type = BufferedImage.TYPE_INT_RGB; type <= BufferedImage.TYPE_BYTE_INDEXED; type++) {
      final BufferedImage source = new BufferedImage(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, type);
      source.setRGB(0, 0, FIRST_COLOR);
      source.setRGB(1, 0, SECOND_COLOR);
      source.setRGB(0, 1, THIRD_COLOR);
      source.setRGB(1, 1, FOURTH_COLOR);
      assertCopiesRasterPixels(source);
    }
  }

  @Test
  void shouldCopyASingleRowWithUnusedScanlinePadding() {
    final BufferedImage standard = new BufferedImage(2, 1, BufferedImage.TYPE_3BYTE_BGR);
    final ColorModel colors = standard.getColorModel();
    final SampleModel model = new PixelInterleavedSampleModel(DataBuffer.TYPE_BYTE, 2, 1, 3, 7, new int[] { 2, 1, 0 });
    final DataBuffer data = new DataBufferByte(6);
    final WritableRaster raster = Raster.createWritableRaster(model, data, new Point());
    final BufferedImage source = new BufferedImage(colors, raster, false, null);
    source.setRGB(0, 0, FIRST_COLOR);
    source.setRGB(1, 0, SECOND_COLOR);
    assertCopiesRasterPixels(source);
  }

  @Test
  void shouldCopyPlanarCustomRastersAndSeparateBanks() {
    final ColorSpace space = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    final int[] transferTypes = { DataBuffer.TYPE_BYTE, DataBuffer.TYPE_INT };
    for (final int type : transferTypes) {
      final ColorModel colors = new ComponentColorModel(space, new int[] { 8, 8, 8 }, false, false, Transparency.OPAQUE, type);
      final SampleModel model = new ComponentSampleModel(type, 2, 2, 1, 2, new int[] { 0, 4, 8 });
      final DataBuffer data = type == DataBuffer.TYPE_BYTE ? new DataBufferByte(12) : new DataBufferInt(12);
      final WritableRaster raster = Raster.createWritableRaster(model, data, new Point());
      final BufferedImage source = new BufferedImage(colors, raster, false, null);
      source.setRGB(0, 0, FIRST_COLOR);
      source.setRGB(1, 1, SECOND_COLOR);
      assertCopiesRasterPixels(source);
    }

    final ColorModel colors = new ComponentColorModel(space, false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
    final WritableRaster raster = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, 2, 2, 3, new Point());
    final BufferedImage source = new BufferedImage(colors, raster, false, null);
    source.setRGB(0, 0, FIRST_COLOR);
    source.setRGB(1, 1, SECOND_COLOR);
    assertCopiesRasterPixels(source);
  }

  @Test
  void shouldCopyOnlyThePixelsOfSubImages() {
    final int[] types = { BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB };
    for (final int type : types) {
      final BufferedImage parent = new BufferedImage(4, 4, type);
      parent.setRGB(1, 1, RED);
      parent.setRGB(0, 1, GREEN);
      parent.setRGB(1, 0, BLUE);
      final BufferedImage shiftedX = parent.getSubimage(1, 0, 2, 2);
      final BufferedImage shiftedY = parent.getSubimage(0, 1, 2, 2);
      final BufferedImage narrower = parent.getSubimage(0, 0, 2, 2);
      assertCopiesRasterPixels(shiftedX);
      assertCopiesRasterPixels(shiftedY);
      assertCopiesRasterPixels(narrower);
      try (
        final ImageBuffer fromShiftedX = ImageBuffer.image(shiftedX);
        final ImageBuffer fromShiftedY = ImageBuffer.image(shiftedY);
        final ImageBuffer fromNarrower = ImageBuffer.image(narrower)
      ) {
        final int shiftedXPixel = argbAt(fromShiftedX, 0, 1);
        final int shiftedYPixel = argbAt(fromShiftedY, 1, 0);
        final int narrowerPixel = argbAt(fromNarrower, 1, 0);
        assertEquals(RED, shiftedXPixel, "type " + type);
        assertEquals(RED, shiftedYPixel, "type " + type);
        assertEquals(BLUE, narrowerPixel, "type " + type);
      }
    }
  }

  @Test
  void shouldConvertBackToBufferedImagesAndReplaceItsContent() {
    try (final ImageBuffer image = Images.indexed(3, 2)) {
      final BufferedImage converted = image.toBufferedImage();
      final int type = converted.getType();
      final int pixel = converted.getRGB(2, 1);
      final BufferedImage sameSize = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
      sameSize.setRGB(0, 0, FIRST_COLOR);
      image.getPixels();
      image.setAsBufferedImage(sameSize);
      final int replaced = argbAt(image, 0, 0);
      final BufferedImage larger = new BufferedImage(5, 4, BufferedImage.TYPE_INT_RGB);
      image.setAsBufferedImage(larger);
      final int width = image.getWidth();
      final int height = image.getHeight();
      assertEquals(BufferedImage.TYPE_3BYTE_BGR, type);
      assertEquals(0xFF000005, pixel);
      assertEquals(FIRST_COLOR, replaced);
      assertEquals(5, width);
      assertEquals(4, height);
      assertThrows(NullPointerException.class, () -> image.setAsBufferedImage(null));
    }
  }

  @Test
  void shouldDescribeTheActualValidRangeInCoordinateDiagnostics() {
    try (final ImageBuffer image = Images.solid(3, 4, BLACK)) {
      final IllegalArgumentException horizontal = assertThrows(IllegalArgumentException.class, () -> image.getPixel(3, 0));
      final IllegalArgumentException vertical = assertThrows(IllegalArgumentException.class, () -> image.getPixel(0, 4));
      final String horizontalMessage = horizontal.getMessage();
      final String verticalMessage = vertical.getMessage();
      assertEquals("x 3 must be between 0 and 2!", horizontalMessage);
      assertEquals("y 4 must be between 0 and 3!", verticalMessage);
    }
  }

  @Test
  void shouldSetAndGetSinglePixels() {
    try (final ImageBuffer image = Images.solid(3, 3, BLACK)) {
      final int black = argbAt(image, 1, 1);
      image.setPixel(1, 1, new double[] { 300.0, -5.0, 127.6 });
      image.setPixel(2, 2, new double[] { 10.0 });
      image.setPixel(0, 0, new double[] { 1.0, 2.0, 3.0 });
      final double[] clamped = image.getPixel(1, 1);
      final double[] partial = image.getPixel(2, 2);
      final double[] firstColumn = image.getPixel(0, 0);
      final int packed = argbAt(image, 1, 1);
      assertEquals(BLACK, black);
      assertArrayEquals(new double[] { CHANNEL_MAXIMUM, 0.0, 128.0 }, clamped);
      assertArrayEquals(new double[] { 10.0, 0.0, 0.0 }, partial);
      assertArrayEquals(new double[] { 1.0, 2.0, 3.0 }, firstColumn, "the first column and row belong to the image");
      assertEquals(0xFF8000FF, packed, "setting a pixel invalidates the packed cache");

      final double[] blackPixel = { 0.0, 0.0, 0.0 };
      assertThrows(IllegalArgumentException.class, () -> image.setPixel(-1, 0, blackPixel));
      assertThrows(IllegalArgumentException.class, () -> image.setPixel(3, 0, blackPixel));
      assertThrows(IllegalArgumentException.class, () -> image.getPixel(0, -1));
      assertThrows(IllegalArgumentException.class, () -> image.getPixel(0, 3));
      assertThrows(NullPointerException.class, () -> image.setPixel(0, 0, null));
    }
  }

  @Test
  void shouldReplaceItsDataWithBgrBytes() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final ByteBuffer sameSize = ByteBuffer.allocate(2 * 2 * 3);
      sameSize.put(0, (byte) 7);
      image.getPixels();
      image.updateData(sameSize, 2, 2);
      final int position = sameSize.position();
      final int first = argbAt(image, 0, 0);
      final ByteBuffer larger = ByteBuffer.allocate(4 * 3);
      image.updateData(larger, 4, 1);
      final int width = image.getWidth();
      final ByteBuffer wrongSize = ByteBuffer.allocate(5);
      final ByteBuffer nothing = ByteBuffer.allocate(0);
      assertEquals(0xFF000007, first);
      assertEquals(0, position, "the data must not be consumed");
      assertEquals(4, width);
      assertThrows(IllegalArgumentException.class, () -> image.updateData(wrongSize, 4, 1));
      assertThrows(IllegalArgumentException.class, () -> image.updateData(wrongSize, 0, 1));
      assertThrows(IllegalArgumentException.class, () -> image.updateData(nothing, 0, 1), "a size of zero is rejected");
      assertThrows(IllegalArgumentException.class, () -> image.updateData(nothing, 1, 0));
      assertThrows(NullPointerException.class, () -> image.updateData(null, 1, 1));
    }
  }

  @Test
  void shouldReplaceItsDataWithPackedPixels() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final int[] sameSize = { 1, 2, 3, 4 };
      image.updateArgb(sameSize, 2, 2);
      final int[] afterSameSize = image.getPixels();
      final int[] larger = Images.indexedPixels(3, 3);
      image.updateArgb(larger, 3, 3);
      final int height = image.getHeight();
      final int[] afterLarger = image.getPixels();
      final int[] taller = Images.indexedPixels(3, 4);
      image.updateArgb(taller, 3, 4);
      final int tallerHeight = image.getHeight();
      assertEquals(4, tallerHeight, "same width, different height");
      assertArrayEquals(new int[] { 0xFF000001, 0xFF000002, 0xFF000003, 0xFF000004 }, afterSameSize, "the alpha channel is discarded");
      assertArrayEquals(larger, afterLarger);
      assertNotSame(larger, afterLarger, "the input is copied");
      assertEquals(3, height);
      assertThrows(IllegalArgumentException.class, () -> image.updateArgb(new int[2], 3, 3));
      assertThrows(IllegalArgumentException.class, () -> image.updateArgb(new int[0], 0, 3));
      assertThrows(NullPointerException.class, () -> image.updateArgb(null, 1, 1));
    }
  }

  @Test
  void shouldDetectMatsThatWereReallocatedBehindItsBack() {
    try (final ImageBuffer image = Images.indexed(4, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final int[] cached = image.getPixels();
      final int[] cachedAgain = image.getPixels();
      final Mat mat = buffer.getMat();
      final Size size = new Size(2, 1);
      opencv_imgproc.resize(mat, mat, size);
      final int[] afterResize = image.getPixels();
      assertSame(cached, cachedAgain, "unchanged images reuse the cache");
      assertEquals(2, afterResize.length);
    }

    try (final ImageBuffer image = Images.indexed(4, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      image.getPixels();
      final Mat mat = buffer.getMat();
      final Mat reshaped = mat.reshape(3, 4);
      mat.put(reshaped);
      final int[] afterReshape = image.getPixels();
      final int width = image.getWidth();
      assertEquals(2, width, "same memory, different shape");
      assertEquals(8, afterReshape.length);

      mat.pop_back(1);
      final int[] afterPop = image.getPixels();
      assertEquals(6, afterPop.length, "same memory and width, fewer rows");
    }
  }

  @Test
  void shouldRefreshTheCacheWhenAskedTo() {
    try (final ImageBuffer image = Images.solid(1, 1, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      image.getPixels();
      final Mat mat = buffer.getMat();
      opencv_core.bitwise_not(mat, mat);
      image.invalidateCache();
      final int inverted = argbAt(image, 0, 0);
      assertEquals(WHITE, inverted);
    }
  }

  @Test
  void shouldReplaceItsMat() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat own = buffer.getMat();
      image.getPixels();
      opencv_core.bitwise_not(own, own);
      buffer.setMat(own);
      final int invertedInPlace = argbAt(image, 0, 0);
      final Mat gray = new Mat(3, 3, opencv_core.CV_8UC1, new Scalar(10.0));
      buffer.setMat(gray);
      final int width = image.getWidth();
      final int grayPixel = argbAt(image, 0, 0);
      final boolean previousReleased = own.empty();
      assertEquals(WHITE, invertedInPlace, "setting the own mat invalidates the cache");
      assertEquals(3, width);
      assertEquals(0xFF0A0A0A, grayPixel);
      assertTrue(previousReleased, "the matrix that was replaced is released");

      try (final Mat empty = new Mat()) {
        assertThrows(IllegalArgumentException.class, () -> buffer.setMat(empty));
      }

      assertThrows(NullPointerException.class, () -> buffer.setMat(null));
    }
  }

  @Test
  void shouldRefreshTheCacheWhenItsMatIsReplacedByAnotherHeaderOfTheSamePixels() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat own = buffer.getMat();
      final Mat sharing = new Mat(own);
      image.getPixels();
      opencv_core.bitwise_not(sharing, sharing);

      buffer.setMat(sharing);

      final int inverted = argbAt(image, 0, 0);
      assertEquals(WHITE, inverted);
    }
  }

  @Test
  void shouldRejectABufferedImageThatReportsNoWidth() {
    final IllegalArgumentException failure = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.image(new ReportedSizeImage(0, 1))
    );
    final String message = failure.getMessage();
    assertEquals("Image dimensions 0x1 must be positive!", message);
  }

  private static long pixelAddress(final Mat mat) {
    final BytePointer data = mat.data();
    return data.address();
  }

  @Test
  void shouldTransformIntoItsSpareMatrixAndReuseItForTheNextFrame() {
    try (final ImageBuffer image = Images.indexed(3, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      final long originalAddress = pixelAddress(original);
      buffer.transformMat(opencv_core::transpose);
      final Mat transposed = buffer.getMat();
      final int width = image.getWidth();
      final int moved = argbAt(image, 1, 0);
      buffer.transformMat(opencv_core::transpose);
      final Mat back = buffer.getMat();
      final long backAddress = pixelAddress(back);
      final int[] restored = image.getPixels();
      buffer.transformMat(opencv_core::transpose);
      final Mat again = buffer.getMat();
      final int[] expected = Images.indexedPixels(3, 2);
      assertEquals(2, width);
      assertEquals(0xFF000003, moved, "the pixel below the first one moves right of it");
      assertArrayEquals(expected, restored);
      assertSame(original, back, "the previous matrix receives the next result");
      assertEquals(originalAddress, backAddress, "without reallocating its pixels");
      assertSame(transposed, again, "so two matrices take turns and none is allocated per frame");
    }
  }

  @Test
  void shouldReuseTheSpareWhenResizingBackToItsSize() {
    try (final ImageBuffer image = Images.indexed(4, 2); final Size half = new Size(2, 1)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      buffer.transformMat((source, target) -> opencv_imgproc.resize(source, target, half));
      final Mat resized = buffer.getMat();
      final int[] frame = Images.indexedPixels(4, 2);
      image.updateArgb(frame, 4, 2);
      final Mat refilled = buffer.getMat();
      final int[] refilledPixels = image.getPixels();
      buffer.setSize(2, 1);
      final Mat shrunk = buffer.getMat();
      final int shrunkWidth = image.getWidth();
      assertSame(original, refilled, "refilling at the original size swaps the spare back in");
      assertArrayEquals(frame, refilledPixels);
      assertSame(resized, shrunk, "and setting the size of the other matrix swaps it back again");
      assertEquals(2, shrunkWidth);
    }
  }

  @Test
  void shouldKeepTheLatestMatrixAsSpareWhenResizedToANewSize() {
    try (final ImageBuffer image = Images.indexed(4, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      buffer.transformMat(opencv_core::transpose);
      final Mat transposed = buffer.getMat();
      buffer.setSize(3, 3);
      final boolean originalReleased = original.empty();
      final int width = image.getWidth();
      buffer.setSize(2, 4);
      final Mat afterwards = buffer.getMat();
      assertTrue(originalReleased, "the older spare is released");
      assertEquals(3, width);
      assertSame(transposed, afterwards, "the matrix used before the new size became the spare");
    }
  }

  @Test
  void shouldReleaseThePreviousMatrixOfImagesWithoutSpare() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BLACK)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      image.updateArgb(new int[9], 3, 3);
      final boolean originalReleased = original.empty();
      final int[] before = image.getPixels();
      buffer.setSize(3, 3);
      final int[] after = image.getPixels();
      assertTrue(originalReleased, "images that never transformed keep no second matrix");
      assertSame(before, after, "setting the current size changes nothing and keeps the cache");
      assertThrows(IllegalArgumentException.class, () -> buffer.setSize(0, 1));
      assertThrows(IllegalArgumentException.class, () -> buffer.setSize(1, 0));
    }
  }

  @Test
  void shouldKeepItsMatrixWhenATransformationFails() {
    try (final ImageBuffer image = Images.indexed(2, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      final int[] expected = Images.indexedPixels(2, 2);
      assertThrows(IllegalStateException.class, () -> buffer.transformMat((_, _) -> failTransformation()));
      assertThrows(IllegalArgumentException.class, () -> buffer.transformMat((_, _) -> leaveTargetEmpty()));
      assertThrows(IllegalArgumentException.class, () -> buffer.transformMat((_, target) -> target.create(2, 2, opencv_core.CV_8UC2)));

      final Mat afterFailures = buffer.getMat();
      final int[] pixels = image.getPixels();
      buffer.transformMat(opencv_core::transpose);
      final int moved = argbAt(image, 1, 0);
      assertSame(original, afterFailures);
      assertArrayEquals(expected, pixels);
      assertEquals(0xFF000002, moved, "the image still transforms after failed transformations");
      assertThrows(NullPointerException.class, () -> buffer.transformMat(null));
    }
  }

  @Test
  void shouldFreeTheMatrixOfAFailedTransformationAtOnce() {
    try (final ImageBuffer image = Images.indexed(2, 2)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final AtomicReference<Mat> given = new AtomicReference<>();
      assertThrows(IllegalArgumentException.class, () -> buffer.transformMat((_, target) -> given.set(target)));

      final Mat target = given.get();
      final boolean freed = target.isNull();
      assertTrue(freed, "the matrix the operation left empty is freed rather than left to the garbage collector");
    }
  }

  // Writes a result that shares the pixels of the source and changes them, so the image keeps its pixel memory but
  // shows other colors.
  private static void shareAndInvert(final Mat source, final Mat target) {
    target.put(source);
    opencv_core.bitwise_not(target, target);
  }

  private static void failTransformation() {
    throw new IllegalStateException("The transformation failed!");
  }

  private static void leaveTargetEmpty() {}

  @Test
  void shouldConvertResultsToBgrAndNeverKeepAMatrixTheResultShares() {
    try (final ImageBuffer image = Images.solid(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, 0xFF102030)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      buffer.transformMat((source, target) -> opencv_imgproc.cvtColor(source, target, opencv_imgproc.COLOR_BGR2GRAY));
      final Mat gray = buffer.getMat();
      final int channels = gray.channels();
      final int grayPixel = argbAt(image, 0, 0);
      buffer.transformMat(MatImageBufferTest::shareAndInvert);
      final boolean grayReleased = gray.empty();
      final int sharedPixel = argbAt(image, 1, 1);
      assertEquals(3, channels, "a single-channel result is converted to BGR");
      assertEquals(0xFF1D1D1D, grayPixel);
      assertTrue(grayReleased, "a matrix whose pixels the result shares must not become the spare");
      assertEquals(0xFFE2E2E2, sharedPixel, "a result that shares the pixels of the image still invalidates the cache");
    }
  }

  @Test
  void shouldReleaseItsSpareMatrixWithItself() {
    try (final ImageBuffer image = Images.indexed(2, 1)) {
      final MatImageBuffer buffer = (MatImageBuffer) image;
      final Mat original = buffer.getMat();
      buffer.transformMat(opencv_core::transpose);
      image.release();
      final boolean spareReleased = original.empty();
      assertTrue(spareReleased);
      assertThrows(IllegalStateException.class, () -> buffer.transformMat(opencv_core::transpose));
      assertThrows(IllegalStateException.class, () -> buffer.setSize(1, 1));
    }
  }

  @Test
  void shouldLeaveTheOriginalUnchangedWhenItsCopyChanges() {
    try (final ImageBuffer image = Images.solid(1, 1, GREEN)) {
      final ImageBuffer copy = image.copy();
      copy.updateArgb(new int[] { BLACK }, 1, 1);
      final int original = argbAt(image, 0, 0);
      final int copied = argbAt(copy, 0, 0);
      copy.release();
      assertEquals(GREEN, original);
      assertEquals(BLACK, copied);
    }
  }

  @Test
  void shouldRefuseToBeUsedAfterRelease() {
    final ImageBuffer image = Images.solid(1, 1, BLACK);
    final MatImageBuffer buffer = (MatImageBuffer) image;
    final Mat own = buffer.getMat();
    image.release();
    image.release();
    image.close();
    final boolean matReleased = own.empty();
    assertTrue(matReleased, "releasing the image releases its matrix");
    assertThrows(IllegalStateException.class, image::getWidth);
    assertThrows(IllegalStateException.class, image::getHeight);
    assertThrows(IllegalStateException.class, image::getPixelCount);
    assertThrows(IllegalStateException.class, image::getPixels);
    assertThrows(IllegalStateException.class, image::getData);
    assertThrows(IllegalStateException.class, image::copy);
    assertThrows(IllegalStateException.class, image::toBufferedImage);
    assertThrows(IllegalStateException.class, buffer::getMat);
    assertThrows(IllegalStateException.class, () -> image.getPixel(0, 0));
    assertThrows(IllegalStateException.class, () -> image.setPixel(0, 0, new double[3]));
    assertThrows(IllegalStateException.class, () -> image.updateArgb(new int[1], 1, 1));
    assertThrows(IllegalStateException.class, () -> image.updateData(ByteBuffer.allocate(3), 1, 1));
    assertThrows(IllegalStateException.class, () -> image.setAsBufferedImage(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)));
    assertThrows(IllegalStateException.class, () -> buffer.setMat(new Mat(1, 1, opencv_core.CV_8UC3)));
  }

  @ParameterizedTest
  @CsvSource({
    "715827883, 1, Image dimensions 715827883x1 exceed the maximum BGR buffer size!",
    "65536, 65536, Image dimensions 65536x65536 exceed the maximum BGR buffer size!",
    "2147483647, 2147483647, Image dimensions 2147483647x2147483647 exceed the maximum BGR buffer size!",
  })
  void shouldRejectUnrepresentableDimensionsBeforeNativeAllocation(final int width, final int height, final String expectedMessage) {
    // two elements differ from both wrapped counts of every row (pixels 715827883, 0 and 1, BGR -2147483647, 0 and 3),
    // so without the dimension guard the length guard still refuses before anything is allocated
    final byte[] bytes = new byte[2];
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final int[] pixels = new int[2];
    final IllegalArgumentException fromBytes = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.bytes(bytes, width, height)
    );
    final IllegalArgumentException fromBuffer = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.bytes(buffer, width, height)
    );
    final IllegalArgumentException fromArgb = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.buffer(pixels, width, height)
    );
    final IllegalArgumentException fromImage = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
      ImageBuffer.image(new ReportedSizeImage(width, height))
    );
    final String bytesMessage = fromBytes.getMessage();
    final String bufferMessage = fromBuffer.getMessage();
    final String argbMessage = fromArgb.getMessage();
    final String imageMessage = fromImage.getMessage();
    assertEquals(expectedMessage, bytesMessage);
    assertEquals(expectedMessage, bufferMessage);
    assertEquals(expectedMessage, argbMessage);
    assertEquals(expectedMessage, imageMessage);

    try (final ImageBuffer image = Images.solid(1, 1, FIRST_COLOR)) {
      final IllegalArgumentException updateBytes = assertThrows(IllegalArgumentException.class, () ->
        image.updateData(buffer, width, height)
      );
      final IllegalArgumentException updatePixels = assertThrows(IllegalArgumentException.class, () ->
        image.updateArgb(pixels, width, height)
      );
      final String updateBytesMessage = updateBytes.getMessage();
      final String updatePixelsMessage = updatePixels.getMessage();
      assertEquals(expectedMessage, updateBytesMessage);
      assertEquals(expectedMessage, updatePixelsMessage);

      final int retainedWidth = image.getWidth();
      final int retainedHeight = image.getHeight();
      final int retainedPixel = argbAt(image, 0, 0);
      assertEquals(1, retainedWidth);
      assertEquals(1, retainedHeight);
      assertEquals(FIRST_COLOR, retainedPixel);
    }
  }

  @Test
  void shouldPermitTheLargestRepresentablePackedSizeToReachTheLengthCheck() {
    final byte[] shortInput = new byte[2];
    // the length error shows the inclusive boundary passes the size check, without allocating its 2147483646 bytes;
    // matrices are intercepted too, so that without the length guard nothing allocates 2 GiB of native memory
    try (final MockedConstruction<Mat> allocations = Mockito.mockConstruction(Mat.class, (_, _) -> this.failMatrixConstruction())) {
      final IllegalArgumentException failure = assertThrowsWhileOpening(IllegalArgumentException.class, () ->
        ImageBuffer.bytes(shortInput, 715827882, 1)
      );
      final String message = failure.getMessage();
      assertEquals("Byte count 2 must be 2147483646!", message);

      final List<Mat> constructed = allocations.constructed();
      final int count = constructed.size();
      assertEquals(0, count);
    }
  }

  private void failMatrixConstruction() {
    throw new AssertionError("The length guard must reject before constructing a native matrix!");
  }

  @Test
  void shouldRejectInvalidPackedPixels() {
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.buffer(new int[3], 2, 2));
    assertThrowsWhileOpening(IllegalArgumentException.class, () -> ImageBuffer.buffer(new int[0], 0, 2));
    assertThrowsWhileOpening(NullPointerException.class, () -> ImageBuffer.buffer(null, 1, 1));
  }

  @Test
  void shouldDiscardTheAlphaChannelOfPackedPixels() {
    final int[] translucent = { 0x00123456, 0x80ABCDEF };
    final int[] expected = { 0xFF123456, 0xFFABCDEF };
    try (final ImageBuffer image = ImageBuffer.buffer(translucent, 2, 1)) {
      final int[] cached = image.getPixels();
      final int[] cachedCopy = cached.clone();
      image.invalidateCache();
      final int[] reread = image.getPixels();
      image.updateArgb(translucent, 2, 1);
      final int[] updated = image.getPixels();
      assertArrayEquals(expected, cachedCopy, "the cached pixels are opaque like the image");
      assertArrayEquals(expected, reread);
      assertArrayEquals(expected, updated);
    }

    final int firstInput = translucent[0];
    assertEquals(0x00123456, firstInput, "the input is not modified");
  }

  private static final class ReportedSizeImage extends BufferedImage {

    private final int width;
    private final int height;

    ReportedSizeImage(final int width, final int height) {
      super(1, 1, BufferedImage.TYPE_3BYTE_BGR);
      this.width = width;
      this.height = height;
    }

    @Override
    public int getWidth() {
      return this.width;
    }

    @Override
    public int getHeight() {
      return this.height;
    }
  }

  private static final class ConversionCountingImage extends BufferedImage {

    private int conversions;

    ConversionCountingImage() {
      super(SMALL_IMAGE_SIZE, SMALL_IMAGE_SIZE, BufferedImage.TYPE_3BYTE_BGR);
    }

    @Override
    public int[] getRGB(
      final int startX,
      final int startY,
      final int width,
      final int height,
      final int @Nullable [] pixels,
      final int offset,
      final int scanlineSize
    ) {
      this.conversions++;
      return super.getRGB(startX, startY, width, height, pixels, offset, scanlineSize);
    }

    int getConversions() {
      return this.conversions;
    }
  }
}
