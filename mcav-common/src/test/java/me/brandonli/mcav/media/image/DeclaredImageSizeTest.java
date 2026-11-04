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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.bmp.BMPImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import me.brandonli.mcav.media.image.DeclaredImageSize.Size;
import me.brandonli.mcav.testing.Images;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// Tests DeclaredImageSize: the size it reads must be the size OpenCV decodes, for every format OpenCV decodes, so that
// a header declaring a huge size is refused before OpenCV allocates it.
final class DeclaredImageSizeTest {

  private static final String SEEDS = "/me/brandonli/mcav/media/image/ImageDecodeFuzzTestInputs/shouldDecodeAnImageOrRefuseTheBytes";
  private static final String CRASH_PREFIX = "crash-";

  private static final String MISSING_FILE_NAME = "missing.png";
  private static final String LARGE_FILE_NAME = "large.png";
  private static final String TEXT_FILE_NAME = "text.png";
  private static final String EMPTY_FILE_NAME = "empty.png";

  private static final String BMP_EXTENSION = ".bmp";
  private static final String JPEG_EXTENSION = ".jpg";
  private static final String PNG_EXTENSION = ".png";
  private static final String WEBP_EXTENSION = ".webp";
  private static final String PPM_EXTENSION = ".ppm";
  private static final String PGM_EXTENSION = ".pgm";
  private static final String PNM_EXTENSION = ".pnm";
  private static final String PAM_EXTENSION = ".pam";
  private static final String PFM_EXTENSION = ".pfm";
  private static final String SUN_RASTER_EXTENSION = ".sr";
  private static final String TIFF_EXTENSION = ".tif";
  private static final String HDR_EXTENSION = ".hdr";
  private static final String GIF_EXTENSION = ".gif";

  private static final String NOT_AN_IMAGE = "not an image";

  private static final String UNSUPPORTED_FILE_MESSAGE = "File %s is not a supported image!";

  private static final String SIZE_TEMPLATE = "%sx%s";
  private static final String DECLARED_LARGE_SIZE = "30000x20000";

  private static final int FILL_BLUE = 10;
  private static final int FILL_GREEN = 200;
  private static final int FILL_RED = 30;

  private static final int LARGE_WIDTH = 30000;
  private static final int LARGE_HEIGHT = 20000;

  private static final int WIDE_IMAGE_WIDTH = 300;
  private static final int WIDE_IMAGE_HEIGHT = 2;

  private static final long MAXIMUM_UNSIGNED_INTEGER = 4294967295L;

  private static final String TIFF_MAGIC_LITTLE_ENDIAN = "II";
  private static final int CLASSIC_TIFF_VERSION = 42;
  private static final int BIG_TIFF_VERSION = 43;
  private static final int CLASSIC_TIFF_DIRECTORY_OFFSET = 8;
  private static final int BIG_TIFF_DIRECTORY_OFFSET = 16;
  private static final int TIFF_IMAGE_WIDTH_TAG = 256;
  private static final int TIFF_IMAGE_LENGTH_TAG = 257;
  private static final int TIFF_SHORT_FIELD_TYPE = 3;
  private static final int TIFF_LONG_FIELD_TYPE = 4;
  private static final int TIFF_LONG_EIGHT_FIELD_TYPE = 16;

  private static final int JPEG_MARKER_PREFIX = 0xFF;
  private static final int JPEG_START_OF_IMAGE = 0xD8;
  private static final int JPEG_START_OF_FRAME = 0xC0;
  private static final int JPEG_APPLICATION_SEGMENT = 0xE0;
  private static final int JPEG_FRAME_HEADER_LENGTH = 17;

  private static final String BMP_SIGNATURE = "BM";
  private static final int BMP_FILE_HEADER_REMAINDER_SIZE = 12;
  private static final int BMP_INFORMATION_HEADER_SIZE = 40;

  private static final String RIFF_TAG = "RIFF";
  private static final int RIFF_SIZE = 30;
  private static final String WEBP_TAG = "WEBP";
  private static final String WEBP_EXTENDED_CHUNK = "VP8X";
  private static final String WEBP_LOSSLESS_CHUNK = "VP8L";
  private static final String WEBP_LOSSY_CHUNK = "VP8 ";
  private static final int WEBP_LOSSLESS_SIGNATURE = 0x2F;
  private static final byte[] WEBP_LOSSY_START_CODE = { (byte) 0x9D, 0x01, 0x2A };

  private static final int SUN_RASTER_MAGIC = 0x59A66A95;

  private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
  private static final int PNG_HEADER_CHUNK_LENGTH = 13;
  private static final String PNG_HEADER_CHUNK = "IHDR";

  private static final int LONG_PAM_LINE_LENGTH = 600;

  @TempDir
  private Path directory;

  private static byte[] bytes(final Object... parts) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (final Object part : parts) {
      if (part instanceof final String text) {
        output.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
      } else if (part instanceof final Integer single) {
        output.write(single);
      } else {
        output.writeBytes((byte[]) part);
      }
    }

    return output.toByteArray();
  }

  private static byte[] littleEndian(final long value, final int count) {
    final byte[] result = new byte[count];
    for (int index = 0; index < count; index++) {
      result[index] = (byte) (value >>> (8 * index));
    }

    return result;
  }

  private static byte[] bigEndian(final long value, final int count) {
    final byte[] result = new byte[count];
    for (int index = 0; index < count; index++) {
      result[count - 1 - index] = (byte) (value >>> (8 * index));
    }

    return result;
  }

  private static @Nullable Size read(final byte[] encoded) {
    return DeclaredImageSize.readDeclaredSize(MemorySegment.ofArray(encoded));
  }

  private static String sizeOf(final @Nullable Size size) {
    return size == null ? "none" : SIZE_TEMPLATE.formatted(size.width(), size.height());
  }

  private static byte[] tiff(final ByteOrder order, final TiffLayout layout) {
    final boolean big = layout == TiffLayout.BIG;
    final int entrySize = big ? 20 : 12;
    final int countSize = big ? 8 : 2;
    final int directory = big ? BIG_TIFF_DIRECTORY_OFFSET : CLASSIC_TIFF_DIRECTORY_OFFSET;
    final int entries = 8;
    final int pixels = directory + countSize + entries * entrySize + (big ? 8 : 4);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(
      order == ByteOrder.LITTLE_ENDIAN
        ? TIFF_MAGIC_LITTLE_ENDIAN.getBytes(StandardCharsets.ISO_8859_1)
        : "MM".getBytes(StandardCharsets.ISO_8859_1)
    );
    output.writeBytes(number(big ? BIG_TIFF_VERSION : CLASSIC_TIFF_VERSION, 2, order));

    if (big) {
      output.writeBytes(number(8, 2, order));
      output.writeBytes(number(0, 2, order));
      output.writeBytes(number(directory, 8, order));
    } else {
      output.writeBytes(number(directory, 4, order));
    }

    output.writeBytes(number(entries, countSize, order));
    final long[][] tags = {
      { TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 3 },
      { TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 2 },
      { 258, TIFF_SHORT_FIELD_TYPE, 1, 8 },
      { 262, TIFF_SHORT_FIELD_TYPE, 1, 2 },
      { 273, TIFF_LONG_FIELD_TYPE, 1, pixels },
      { 277, TIFF_SHORT_FIELD_TYPE, 1, 3 },
      { 278, TIFF_SHORT_FIELD_TYPE, 1, 2 },
      { 279, TIFF_LONG_FIELD_TYPE, 1, 18 },
    };
    for (final long[] tag : tags) {
      output.writeBytes(number(tag[0], 2, order));
      output.writeBytes(number(tag[1], 2, order));
      output.writeBytes(number(tag[2], big ? 8 : 4, order));
      final int width = tag[1] == TIFF_SHORT_FIELD_TYPE ? 2 : 4;
      output.writeBytes(number(tag[3], width, order));
      output.writeBytes(new byte[(big ? 8 : 4) - width]);
    }

    output.writeBytes(new byte[big ? 8 : 4]);
    for (int index = 0; index < 18; index++) {
      output.write(index * 13);
    }

    return output.toByteArray();
  }

  private static byte[] number(final long value, final int count, final ByteOrder order) {
    return order == ByteOrder.LITTLE_ENDIAN ? littleEndian(value, count) : bigEndian(value, count);
  }

  private static Size decodedByOpenCv(final byte[] encoded) {
    try (final BytePointer pointer = new BytePointer(encoded); final Mat wrapper = new Mat(pointer)) {
      final Mat decoded = opencv_imgcodecs.imdecode(wrapper, opencv_imgcodecs.IMREAD_COLOR);
      try {
        final boolean empty = decoded.empty();
        assertFalse(empty, "OpenCV decodes the sample");

        return new Size(decoded.cols(), decoded.rows());
      } finally {
        decoded.release();
      }
    }
  }

  private static void assertDeclaresWhatOpenCvDecodes(final String name, final byte[] encoded) {
    final Size decoded = decodedByOpenCv(encoded);
    final Size declared = read(encoded);
    final String declaredSize = sizeOf(declared);
    final String expected = sizeOf(decoded);
    assertEquals(expected, declaredSize, name);

    final long pixels = decoded.width() * decoded.height();
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(encoded, pixels), name);

    final long below = Math.max(1, pixels - 1);
    final boolean refusedBelow = pixels > 1;
    final boolean refused = refuses(encoded, below);
    assertEquals(refusedBelow, refused, name);
  }

  private static boolean refuses(final byte[] encoded, final long limit) {
    try {
      DeclaredImageSize.checkBytes(encoded, limit);

      return false;
    } catch (final IllegalArgumentException exception) {
      return true;
    }
  }

  static Stream<Arguments> openCvSamples() {
    final List<String> extensions = List.of(
      BMP_EXTENSION,
      JPEG_EXTENSION,
      PNG_EXTENSION,
      WEBP_EXTENSION,
      PPM_EXTENSION,
      PGM_EXTENSION,
      PNM_EXTENSION,
      PAM_EXTENSION,
      PFM_EXTENSION,
      SUN_RASTER_EXTENSION,
      TIFF_EXTENSION,
      HDR_EXTENSION,
      GIF_EXTENSION
    );
    final int[][] sizes = { { 5, 7 }, { WIDE_IMAGE_WIDTH, WIDE_IMAGE_HEIGHT }, { 1, 1 } };
    return extensions.stream().flatMap(extension -> openCvSamplesOfExtension(extension, sizes));
  }

  private static Stream<Arguments> openCvSamplesOfExtension(final String extension, final int[][] sizes) {
    return Stream.of(sizes).flatMap(size -> openCvSamplesOfSize(extension, size));
  }

  private static Stream<Arguments> openCvSamplesOfSize(final String extension, final int[] size) {
    return Stream.of(opencv_core.CV_8UC1, opencv_core.CV_8UC3).map(type -> Arguments.of(extension, size[0], size[1], type));
  }

  @ParameterizedTest
  @MethodSource("openCvSamples")
  void shouldDeclareTheSizeOpenCvDecodesForEveryFormatOpenCvWrites(
    final String extension,
    final int width,
    final int height,
    final int type
  ) {
    final boolean gray = type == opencv_core.CV_8UC1;
    final boolean colorOnly = extension.equals(PPM_EXTENSION) || extension.equals(GIF_EXTENSION);
    final boolean grayOnly = extension.equals(PGM_EXTENSION);
    if ((gray && colorOnly) || (!gray && grayOnly)) {
      return;
    }

    try (
      final Mat picture = new Mat(height, width, type, new Scalar(FILL_BLUE, FILL_GREEN, FILL_RED, 0));
      final BytePointer output = new BytePointer()
    ) {
      final boolean written = opencv_imgcodecs.imencode(extension, picture, output);
      assertTrue(written, extension);
      final byte[] encoded = new byte[(int) output.limit()];
      output.get(encoded);
      assertDeclaresWhatOpenCvDecodes("%s %sx%s".formatted(extension, width, height), encoded);
    }
  }

  @Test
  void shouldDeclareTheSizeOpenCvDecodesForLosslessWebpAndTextNetpbm() {
    try (final Mat picture = new Mat(7, 5, opencv_core.CV_8UC1, new Scalar(90)); final BytePointer output = new BytePointer()) {
      try (final IntPointer lossless = new IntPointer(opencv_imgcodecs.IMWRITE_WEBP_QUALITY, 101)) {
        final boolean encoded = opencv_imgcodecs.imencode(WEBP_EXTENSION, picture, output, lossless);
        assertTrue(encoded);
      }
      final byte[] webp = new byte[(int) output.limit()];
      output.get(webp);
      assertDeclaresWhatOpenCvDecodes("lossless webp", webp);
      try (final IntPointer text = new IntPointer(opencv_imgcodecs.IMWRITE_PXM_BINARY, 0)) {
        final boolean encoded = opencv_imgcodecs.imencode(PGM_EXTENSION, picture, output, text);
        assertTrue(encoded);
      }
      final byte[] pgm = new byte[(int) output.limit()];
      output.get(pgm);
      assertDeclaresWhatOpenCvDecodes("text pgm", pgm);
    }
  }

  @Test
  void shouldSkipAStuffedZeroBetweenMarkersAsLibjpegSkipsIt() {
    try (
      final Mat picture = new Mat(WIDE_IMAGE_HEIGHT, WIDE_IMAGE_WIDTH, opencv_core.CV_8UC3, new Scalar(FILL_BLUE, FILL_GREEN, FILL_RED, 0));
      final BytePointer output = new BytePointer()
    ) {
      final boolean encoded = opencv_imgcodecs.imencode(JPEG_EXTENSION, picture, output);
      assertTrue(encoded);
      final byte[] jpeg = new byte[(int) output.limit()];
      output.get(jpeg);
      final int decoy = 4 + (((jpeg[2] & 0xFF) << 8) | (jpeg[3] & 0xFF));
      final byte[] frame = bytes(
        JPEG_MARKER_PREFIX,
        JPEG_START_OF_FRAME,
        bigEndian(JPEG_FRAME_HEADER_LENGTH, 2),
        8,
        bigEndian(1, 2),
        bigEndian(1, 2),
        3,
        1,
        0x22,
        0,
        2,
        0x11,
        1,
        3,
        0x11,
        1
      );
      final byte[] crafted = new byte[decoy + frame.length];
      System.arraycopy(bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, 0x00), 0, crafted, 0, 4);
      System.arraycopy(jpeg, 2, crafted, 4, jpeg.length - 2);
      System.arraycopy(frame, 0, crafted, decoy, frame.length);
      assertDeclaresWhatOpenCvDecodes("a stuffed zero before the first marker", crafted);
    }
  }

  @Test
  void shouldDeclareTheSizeOpenCvDecodesForTheFuzzSeeds() throws IOException, URISyntaxException {
    final URL seeds = DeclaredImageSizeTest.class.getResource(SEEDS);
    assertTrue(seeds != null, "the seed folder is on the class path");

    final Path folder = Path.of(seeds.toURI());
    try (final Stream<Path> files = Files.list(folder)) {
      final Stream<Path> seedFiles = files.filter(DeclaredImageSizeTest::isSeed);
      final List<Path> samples = seedFiles.sorted().toList();
      final int sampleCount = samples.size();
      assertEquals(10, sampleCount);

      for (final Path sample : samples) {
        assertDeclaresWhatOpenCvDecodes(sample.getFileName().toString(), Files.readAllBytes(sample));
      }
    }
  }

  private static boolean isSeed(final Path sample) {
    final Path fileName = sample.getFileName();
    final String name = fileName.toString();
    return !name.startsWith(CRASH_PREFIX);
  }

  @Test
  void shouldDeclareTheSizeOpenCvDecodesForFilesImageIoWrites() throws IOException {
    final BufferedImage picture = new BufferedImage(6, 4, BufferedImage.TYPE_3BYTE_BGR);
    picture.setRGB(2, 1, 0x3366CC);
    for (final String format : List.of("png", "jpg", "bmp", "gif", "tiff")) {
      assertDeclaresWhatOpenCvDecodes(format, Images.encode(picture, format));
    }

    final ImageWriter writer = ImageIO.getImageWritersByFormatName("bmp").next();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (final ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(stream);
      final BMPImageWriteParam topDown = (BMPImageWriteParam) writer.getDefaultWriteParam();
      topDown.setTopDown(true);
      topDown.setCompressionMode(ImageWriteParam.MODE_DISABLED);
      writer.write(null, new IIOImage(picture, null, null), topDown);
    } finally {
      writer.dispose();
    }

    final byte[] bmp = output.toByteArray();
    final boolean negativeHeight = bmp[25] < 0;
    assertTrue(negativeHeight, "the height of a top-down BMP is negative");
    assertDeclaresWhatOpenCvDecodes("top-down bmp", bmp);
  }

  @Test
  void shouldDeclareTheSizeOpenCvDecodesForTiffsOfBothByteOrdersAndBigTiffs() {
    assertDeclaresWhatOpenCvDecodes("little-endian tiff", tiff(ByteOrder.LITTLE_ENDIAN, TiffLayout.CLASSIC));
    assertDeclaresWhatOpenCvDecodes("big-endian tiff", tiff(ByteOrder.BIG_ENDIAN, TiffLayout.CLASSIC));
    assertDeclaresWhatOpenCvDecodes("little-endian bigtiff", tiff(ByteOrder.LITTLE_ENDIAN, TiffLayout.BIG));
    assertDeclaresWhatOpenCvDecodes("big-endian bigtiff", tiff(ByteOrder.BIG_ENDIAN, TiffLayout.BIG));
  }

  private static byte[] tiffEntry(final TiffLayout layout, final long tag, final long type, final long count, final long value) {
    final boolean big = layout == TiffLayout.BIG;
    final int width = type == TIFF_SHORT_FIELD_TYPE ? 2 : type == TIFF_LONG_EIGHT_FIELD_TYPE ? 8 : 4;
    final int field = big ? 8 : 4;
    return bytes(
      littleEndian(tag, 2),
      littleEndian(type, 2),
      littleEndian(count, big ? 8 : 4),
      littleEndian(value, width),
      new byte[Math.max(0, field - width)]
    );
  }

  private static byte[] classicTiff(final byte[]... entries) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(
      bytes(
        TIFF_MAGIC_LITTLE_ENDIAN,
        littleEndian(CLASSIC_TIFF_VERSION, 2),
        littleEndian(CLASSIC_TIFF_DIRECTORY_OFFSET, 4),
        littleEndian(entries.length, 2)
      )
    );
    for (final byte[] entry : entries) {
      output.writeBytes(entry);
    }

    return output.toByteArray();
  }

  private static byte[] pad(final byte[] head) {
    final byte[] padded = new byte[Math.max(32, head.length)];
    System.arraycopy(head, 0, padded, 0, head.length);
    return padded;
  }

  private static byte[] lossless(final int width, final int height, final int version) {
    return littleEndian((width - 1) | ((long) (height - 1) << 14) | ((long) version << 29), 4);
  }

  static Stream<Arguments> declaredSizes() {
    return Stream.of(
      Arguments.of(
        "bmp",
        bytes(
          BMP_SIGNATURE,
          new byte[BMP_FILE_HEADER_REMAINDER_SIZE],
          littleEndian(BMP_INFORMATION_HEADER_SIZE, 4),
          littleEndian(LARGE_WIDTH, 4),
          littleEndian(LARGE_HEIGHT, 4)
        ),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of(
        "top-down bmp",
        bytes(
          BMP_SIGNATURE,
          new byte[BMP_FILE_HEADER_REMAINDER_SIZE],
          littleEndian(124, 4),
          littleEndian(LARGE_WIDTH, 4),
          littleEndian(-LARGE_HEIGHT, 4)
        ),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of(
        "os/2 bmp",
        bytes(BMP_SIGNATURE, new byte[BMP_FILE_HEADER_REMAINDER_SIZE], littleEndian(12, 4), littleEndian(65535, 2), littleEndian(300, 2)),
        65535,
        300
      ),
      Arguments.of(
        "bmp of the least height",
        bytes(
          BMP_SIGNATURE,
          new byte[BMP_FILE_HEADER_REMAINDER_SIZE],
          littleEndian(BMP_INFORMATION_HEADER_SIZE, 4),
          littleEndian(5, 4),
          littleEndian(Integer.MIN_VALUE, 4)
        ),
        5,
        2147483648L
      ),
      Arguments.of("gif89a", bytes("GIF89a", littleEndian(65535, 2), littleEndian(32768, 2)), 65535, 32768),
      Arguments.of("gif87a", bytes("GIF87a", littleEndian(5, 2), littleEndian(7, 2)), 5, 7),
      Arguments.of("radiance", bytes("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 20000 +X 30000\n"), LARGE_WIDTH, LARGE_HEIGHT),
      Arguments.of("rgbe with a comment and signs", bytes("#?RGBE\n# made by hand\n\n-Y\t+7 +X 5\n"), 5, 7),
      Arguments.of("hdr line longer than a piece", bytes("#?RGBE\n#", "x".repeat(200), "\n\n-Y 2 +X 3\n"), 3, 2),
      // OpenCV reads a 127-character line and its line feed as two pieces, the second of which ends the header
      Arguments.of("hdr piece ending the header", bytes("#?RGBE\n", "F".repeat(127), "\n-Y 4 +X 6\n"), 6, 4),
      Arguments.of(
        "jpeg",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          JPEG_APPLICATION_SEGMENT,
          bigEndian(16, 2),
          new byte[14],
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_FRAME,
          bigEndian(JPEG_FRAME_HEADER_LENGTH, 2),
          8,
          bigEndian(LARGE_HEIGHT, 2),
          bigEndian(LARGE_WIDTH, 2)
        ),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of(
        "jpeg with garbage, fill bytes and standalone markers",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          0x01,
          0x00,
          0x12,
          JPEG_MARKER_PREFIX,
          JPEG_MARKER_PREFIX,
          0xD0,
          JPEG_MARKER_PREFIX,
          0xD7,
          JPEG_MARKER_PREFIX,
          JPEG_MARKER_PREFIX,
          0xC2,
          bigEndian(11, 2),
          8,
          bigEndian(7, 2),
          bigEndian(5, 2)
        ),
        5,
        7
      ),
      Arguments.of(
        "jpeg after tables with lengths",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          0xC4,
          bigEndian(3, 2),
          0,
          JPEG_MARKER_PREFIX,
          0xC8,
          bigEndian(2, 2),
          JPEG_MARKER_PREFIX,
          0xCC,
          bigEndian(4, 2),
          0,
          0,
          JPEG_MARKER_PREFIX,
          0xB0,
          bigEndian(2, 2),
          JPEG_MARKER_PREFIX,
          0xE1,
          bigEndian(2, 2),
          JPEG_MARKER_PREFIX,
          0xC1,
          bigEndian(11, 2),
          8,
          bigEndian(9, 2),
          bigEndian(4, 2)
        ),
        4,
        9
      ),
      Arguments.of(
        "extended webp",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_EXTENDED_CHUNK,
            littleEndian(10, 4),
            littleEndian(16, 4),
            littleEndian(16383, 3),
            littleEndian(9999, 3)
          )
        ),
        16384,
        10000
      ),
      Arguments.of(
        "lossless webp",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_LOSSLESS_CHUNK,
            littleEndian(5, 4),
            WEBP_LOSSLESS_SIGNATURE,
            lossless(300, 200, 0)
          )
        ),
        300,
        200
      ),
      Arguments.of("raw lossless webp", pad(bytes(WEBP_LOSSLESS_SIGNATURE, lossless(16384, 16384, 0))), 16384, 16384),
      Arguments.of(
        "lossy webp",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_LOSSY_CHUNK,
            littleEndian(10, 4),
            0x10,
            0x02,
            0x00,
            WEBP_LOSSY_START_CODE,
            littleEndian(0xC000 | 16383, 2),
            littleEndian(0x4000 | 300, 2)
          )
        ),
        16383,
        300
      ),
      Arguments.of(
        "sun raster",
        bytes(bigEndian(SUN_RASTER_MAGIC, 4), bigEndian(LARGE_WIDTH, 4), bigEndian(LARGE_HEIGHT, 4)),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of("ppm", bytes("P6\n30000 20000\n255\n"), LARGE_WIDTH, LARGE_HEIGHT),
      Arguments.of("pbm with comments", bytes("P1 # made by hand\r5\t\n 7\n"), 5, 7),
      Arguments.of("pbm with comments on their own lines", bytes("P4\n#one\n#two\n3 4"), 3, 4),
      Arguments.of(
        "pam",
        bytes(
          "P7\n# made by hand\r\n\nWIDTH 30000\n  HEIGHT   20000  \nDEPTH 3\nMAXVAL 255\nTUPLTYPE RGB\n#",
          "c".repeat(LONG_PAM_LINE_LENGTH),
          "\nENDHDR\n"
        ),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of("pfm", bytes("PF\n30000 20000\n-1.0\n"), LARGE_WIDTH, LARGE_HEIGHT),
      Arguments.of("pfm read like atoi", bytes("Pf\n+5 7abc\n1\n"), 5, 7),
      Arguments.of(
        "tiff",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, 254, TIFF_LONG_FIELD_TYPE, 1, 0),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, LARGE_WIDTH),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_LONG_FIELD_TYPE, 1, LARGE_HEIGHT)
        ),
        LARGE_WIDTH,
        LARGE_HEIGHT
      ),
      Arguments.of(
        "bmp of the shortest info header of 32-bit sizes",
        bytes(BMP_SIGNATURE, new byte[BMP_FILE_HEADER_REMAINDER_SIZE], littleEndian(36, 4), littleEndian(5, 4), littleEndian(7, 4)),
        5,
        7
      ),
      Arguments.of(
        "jpeg of the last kind of frame",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          0xCF,
          bigEndian(JPEG_FRAME_HEADER_LENGTH, 2),
          8,
          bigEndian(7, 2),
          bigEndian(5, 2)
        ),
        5,
        7
      ),
      Arguments.of(
        "jpeg after a segment of only its length",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          JPEG_APPLICATION_SEGMENT,
          bigEndian(2, 2),
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_FRAME,
          bigEndian(JPEG_FRAME_HEADER_LENGTH, 2),
          8,
          bigEndian(7, 2),
          bigEndian(5, 2)
        ),
        5,
        7
      ),
      Arguments.of(
        "jpeg with a stuffed zero before its frame",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          0x00,
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_FRAME,
          bigEndian(JPEG_FRAME_HEADER_LENGTH, 2),
          8,
          bigEndian(7, 2),
          bigEndian(5, 2)
        ),
        5,
        7
      ),
      Arguments.of("pbm with a nul in a comment", bytes("P4 #a", 0, " 1 1\n3 4\n"), 3, 4),
      Arguments.of("ppm of the widest width", bytes("P6\n2147483647 1\n255\n"), 2147483647L, 1),
      Arguments.of("pam of a ten-digit width", bytes("P7\nWIDTH 4294967295\nHEIGHT 1\nENDHDR\n"), MAXIMUM_UNSIGNED_INTEGER, 1),
      Arguments.of("pam with a nul in a comment", bytes("P7\n# a", 0, "b\nWIDTH 5\nHEIGHT 7\nENDHDR\n"), 5, 7),
      Arguments.of("pfm of a signed ten-digit width", bytes("PF\n+1234567890 7\n"), 1234567890, 7),
      Arguments.of("pfm with a nul in its width", bytes("PF\n5", 0, " 7\n"), 5, 7),
      Arguments.of("pfm with a width as long as a number may be", bytes("PF\n5", "a".repeat(2047), "7\n"), 5, 7),
      Arguments.of("hdr of the widest width", bytes("#?RGBE\n\n-Y 5 +X 2147483647\n"), 2147483647L, 5),
      Arguments.of("hdr with a nul in a header line", bytes("#?RGBE\nFORMAT=32-bit_rle_rgbe", 0, "\n\n-Y 7 +X 5\n"), 5, 7),
      Arguments.of(
        "tiff with an entry past its count",
        bytes(
          TIFF_MAGIC_LITTLE_ENDIAN,
          littleEndian(CLASSIC_TIFF_VERSION, 2),
          littleEndian(CLASSIC_TIFF_DIRECTORY_OFFSET, 4),
          littleEndian(2, 2),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 9)
        ),
        5,
        7
      ),
      Arguments.of(
        "png",
        bytes(
          PNG_SIGNATURE,
          bigEndian(PNG_HEADER_CHUNK_LENGTH, 4),
          PNG_HEADER_CHUNK,
          bigEndian(MAXIMUM_UNSIGNED_INTEGER, 4),
          bigEndian(1, 4)
        ),
        MAXIMUM_UNSIGNED_INTEGER,
        1
      )
    );
  }

  @ParameterizedTest
  @MethodSource("declaredSizes")
  void shouldReadTheSizeTheHeaderDeclares(final String name, final byte[] encoded, final long width, final long height) {
    final Size size = read(encoded);
    final String declaredSize = sizeOf(size);
    final String expected = SIZE_TEMPLATE.formatted(width, height);
    assertEquals(expected, declaredSize, name);
  }

  static Stream<Arguments> unreadableHeaders() {
    return Stream.of(
      Arguments.of("nothing", new byte[0]),
      Arguments.of("unknown bytes", bytes("not an image at all, not one")),
      Arguments.of("jpeg 2000", bytes(0, 0, 0, 0x0C, "jP  \r\n", 0x87, "\n", new byte[20])),
      Arguments.of("openexr", bytes(0x76, 0x2F, 0x31, 0x01, new byte[40])),
      Arguments.of(
        "bmp with an unknown info header",
        bytes(BMP_SIGNATURE, new byte[BMP_FILE_HEADER_REMAINDER_SIZE], littleEndian(20, 4), littleEndian(5, 4), littleEndian(7, 4))
      ),
      Arguments.of("truncated bmp", bytes(BMP_SIGNATURE, new byte[4])),
      Arguments.of(
        "bmp without width",
        bytes(
          BMP_SIGNATURE,
          new byte[BMP_FILE_HEADER_REMAINDER_SIZE],
          littleEndian(BMP_INFORMATION_HEADER_SIZE, 4),
          littleEndian(0, 4),
          littleEndian(7, 4)
        )
      ),
      Arguments.of("gif of another version", bytes("GIF88a", littleEndian(5, 2), littleEndian(7, 2))),
      Arguments.of("hdr without the end of its header", bytes("#?RGBE\nFORMAT=32-bit_rle_rgbe\n")),
      Arguments.of("hdr without its resolution", bytes("#?RGBE\n\n")),
      Arguments.of("hdr with another orientation", bytes("#?RGBE\n\n+Y 7 +X 5\n")),
      Arguments.of("hdr without its x", bytes("#?RGBE\n\n-Y 7 X 5\n")),
      Arguments.of("hdr without its width", bytes("#?RGBE\n\n-Y 7 +X\n")),
      Arguments.of("hdr with a huge number", bytes("#?RGBE\n\n-Y 99999999999 +X 5\n")),
      Arguments.of("hdr with a negative height", bytes("#?RGBE\n\n-Y -7 +X 5\n")),
      Arguments.of("hdr cut by a nul", bytes("#?RGBE\n\n-Y 7", 0, " +X 5\n")),
      Arguments.of(
        "jpeg ending before a frame",
        bytes(
          JPEG_MARKER_PREFIX,
          JPEG_START_OF_IMAGE,
          JPEG_MARKER_PREFIX,
          JPEG_APPLICATION_SEGMENT,
          bigEndian(4, 2),
          0,
          0,
          JPEG_MARKER_PREFIX,
          0xD9
        )
      ),
      Arguments.of("jpeg scan before a frame", bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, 0xDA, bigEndian(2, 2))),
      Arguments.of("jpeg starting twice", bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE)),
      Arguments.of("jpeg without markers", bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX)),
      Arguments.of(
        "jpeg running into garbage",
        bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, JPEG_APPLICATION_SEGMENT, bigEndian(4, 2), 0, 0, 0x12, 0x34)
      ),
      Arguments.of(
        "jpeg with a short length",
        bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, JPEG_APPLICATION_SEGMENT, bigEndian(1, 2))
      ),
      Arguments.of("jpeg without a length", bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, JPEG_APPLICATION_SEGMENT)),
      Arguments.of(
        "jpeg frame without its size",
        bytes(JPEG_MARKER_PREFIX, JPEG_START_OF_IMAGE, JPEG_MARKER_PREFIX, JPEG_START_OF_FRAME, bigEndian(JPEG_FRAME_HEADER_LENGTH, 2), 8)
      ),
      Arguments.of(
        "webp shorter than its signature",
        bytes(
          RIFF_TAG,
          littleEndian(RIFF_SIZE, 4),
          WEBP_TAG,
          WEBP_EXTENDED_CHUNK,
          littleEndian(10, 4),
          littleEndian(0, 4),
          littleEndian(5, 3),
          littleEndian(5, 3)
        )
      ),
      Arguments.of("riff of another kind", pad(bytes(RIFF_TAG, littleEndian(RIFF_SIZE, 4), "WAVE", "fmt "))),
      Arguments.of(
        "webp with a broken extended header",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_EXTENDED_CHUNK,
            littleEndian(9, 4),
            littleEndian(0, 4),
            littleEndian(5, 3),
            littleEndian(5, 3)
          )
        )
      ),
      Arguments.of(
        "lossless webp of another version",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_LOSSLESS_CHUNK,
            littleEndian(5, 4),
            WEBP_LOSSLESS_SIGNATURE,
            lossless(5, 7, 1)
          )
        )
      ),
      Arguments.of(
        "lossless webp without its signature",
        pad(bytes(RIFF_TAG, littleEndian(RIFF_SIZE, 4), WEBP_TAG, WEBP_LOSSLESS_CHUNK, littleEndian(5, 4), 0x2E, lossless(5, 7, 0)))
      ),
      Arguments.of(
        "webp with another first chunk",
        pad(bytes(RIFF_TAG, littleEndian(RIFF_SIZE, 4), WEBP_TAG, "ALPH", littleEndian(10, 4), 0x10, 0x02, 0x00, WEBP_LOSSY_START_CODE))
      ),
      Arguments.of(
        "lossy webp of a later frame",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_LOSSY_CHUNK,
            littleEndian(10, 4),
            0x11,
            0x02,
            0x00,
            WEBP_LOSSY_START_CODE
          )
        )
      ),
      Arguments.of(
        "lossy webp without its start code",
        pad(
          bytes(RIFF_TAG, littleEndian(RIFF_SIZE, 4), WEBP_TAG, WEBP_LOSSY_CHUNK, littleEndian(10, 4), 0x10, 0x02, 0x00, 0x9D, 0x01, 0x2B)
        )
      ),
      Arguments.of(
        "lossy webp of no width",
        pad(
          bytes(
            RIFF_TAG,
            littleEndian(RIFF_SIZE, 4),
            WEBP_TAG,
            WEBP_LOSSY_CHUNK,
            littleEndian(10, 4),
            0x10,
            0x02,
            0x00,
            WEBP_LOSSY_START_CODE,
            littleEndian(0xC000, 2)
          )
        )
      ),
      Arguments.of("sun raster of a negative width", bytes(bigEndian(SUN_RASTER_MAGIC, 4), bigEndian(0x80000000L, 4), bigEndian(5, 4))),
      Arguments.of("truncated sun raster", bytes(bigEndian(SUN_RASTER_MAGIC, 4), bigEndian(5, 4))),
      Arguments.of("ppm with a letter for a number", bytes("P6\nx 5\n")),
      Arguments.of("ppm with an endless comment", bytes("P6\n# no end")),
      Arguments.of("ppm without its height", bytes("P6\n5")),
      Arguments.of("ppm with a huge width", bytes("P6\n99999999999 1\n")),
      Arguments.of("ppm of no width", bytes("P6\n0 5\n")),
      Arguments.of("netpbm of an unknown kind", bytes("P8 5 7\n")),
      Arguments.of("netpbm of no kind", bytes("P0 5 7\n")),
      Arguments.of("netpbm magic without space", bytes("P6x5 7\n")),
      Arguments.of("netpbm of another letter", bytes("Pz 5 7\n")),
      Arguments.of("pam with two widths", bytes("P7\nWIDTH 5\nWIDTH 6\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with two heights", bytes("P7\nWIDTH 5\nHEIGHT 6\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with an unknown name", bytes("P7\nWIDTH 5\nHEIGHT 7\nCOLORS 3\nENDHDR\n")),
      Arguments.of("pam without its end", bytes("P7\nWIDTH 5\nHEIGHT 7\n")),
      Arguments.of("pam ending inside a line", bytes("P7\nWIDTH 5\nHEIGHT 7")),
      Arguments.of("pam with a long value", bytes("P7\nTUPLTYPE ", "x".repeat(LONG_PAM_LINE_LENGTH), "\nWIDTH 5\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam without a value", bytes("P7\nWIDTH\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with a huge width", bytes("P7\nWIDTH 12345678901\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with a word for a width", bytes("P7\nWIDTH 12a\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam without its height", bytes("P7\nWIDTH 5\nENDHDR\n")),
      Arguments.of("pfm without its line feed", bytes("PF 5 7\n1\n")),
      Arguments.of("pfm of an empty width", bytes("PF\n 5 7\n")),
      Arguments.of("pfm ending in its height", bytes("PF\n5 7")),
      Arguments.of("pfm of a negative width", bytes("PF\n-5 7\n")),
      Arguments.of("pfm of a width too long for a number", bytes("PF\n", "1".repeat(2048), " 7\n")),
      Arguments.of("pfm of a huge width", bytes("PF\n12345678901 7\n")),
      Arguments.of("pfm of a word for a width", bytes("PF\nabc 7\n")),
      Arguments.of(
        "tiff of another magic",
        bytes(TIFF_MAGIC_LITTLE_ENDIAN, littleEndian(44, 2), littleEndian(CLASSIC_TIFF_DIRECTORY_OFFSET, 4), littleEndian(0, 2))
      ),
      Arguments.of(
        "tiff with its directory past the end",
        bytes(TIFF_MAGIC_LITTLE_ENDIAN, littleEndian(CLASSIC_TIFF_VERSION, 2), littleEndian(4000, 4))
      ),
      Arguments.of(
        "bigtiff with its directory past any file",
        bytes(TIFF_MAGIC_LITTLE_ENDIAN, littleEndian(BIG_TIFF_VERSION, 2), littleEndian(8, 2), littleEndian(0, 2), littleEndian(-1, 8))
      ),
      Arguments.of(
        "tiff with two widths",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 9),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7)
        )
      ),
      Arguments.of(
        "tiff with two lengths",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 9),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7)
        )
      ),
      Arguments.of(
        "tiff with a width of two values",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 2, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7)
        )
      ),
      Arguments.of(
        "tiff with a width in bytes",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, 1, 1, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7)
        )
      ),
      Arguments.of(
        "classic tiff with an eight-byte width",
        classicTiff(
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_LONG_EIGHT_FIELD_TYPE, 1, 5),
          tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 7)
        )
      ),
      Arguments.of(
        "tiff without its length",
        classicTiff(tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, 5))
      ),
      Arguments.of("pam with a width of 0 and another", bytes("P7\nWIDTH 0\nWIDTH 5\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with a height of 0 and another", bytes("P7\nWIDTH 5\nHEIGHT 0\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("pam with a line one longer than its limit", bytes("P7\nWIDTH", " ".repeat(507), "5\nHEIGHT 7\nENDHDR\n")),
      Arguments.of("hdr with an eleven-digit width", bytes("#?RGBE\n\n-Y 5 +X 21474836470\n")),
      Arguments.of(
        "png without its header chunk",
        bytes(PNG_SIGNATURE, bigEndian(PNG_HEADER_CHUNK_LENGTH, 4), "IDAT", bigEndian(5, 4), bigEndian(7, 4))
      ),
      Arguments.of(
        "png of no height",
        bytes(PNG_SIGNATURE, bigEndian(PNG_HEADER_CHUNK_LENGTH, 4), PNG_HEADER_CHUNK, bigEndian(5, 4), bigEndian(0, 4))
      )
    );
  }

  @ParameterizedTest
  @MethodSource("unreadableHeaders")
  void shouldRefuseBytesWhoseHeaderItCannotRead(final String name, final byte[] encoded) {
    final Size declared = read(encoded);
    assertNull(declared, name);

    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkBytes(encoded, 1L << 40)
    );
    final String message = refused.getMessage();
    assertEquals("Bytes are not a supported image format!", message, name);
  }

  @Test
  void shouldReadAnEightByteWidthOfABigTiff() {
    final byte[] entries = bytes(
      tiffEntry(TiffLayout.BIG, TIFF_IMAGE_WIDTH_TAG, TIFF_LONG_EIGHT_FIELD_TYPE, 1, LARGE_WIDTH),
      tiffEntry(TiffLayout.BIG, TIFF_IMAGE_LENGTH_TAG, TIFF_LONG_FIELD_TYPE, 1, LARGE_HEIGHT)
    );
    final byte[] bigTiff = bytes(
      TIFF_MAGIC_LITTLE_ENDIAN,
      littleEndian(BIG_TIFF_VERSION, 2),
      littleEndian(8, 2),
      littleEndian(0, 2),
      littleEndian(BIG_TIFF_DIRECTORY_OFFSET, 8),
      littleEndian(2, 8),
      entries
    );
    final String declaredSize = sizeOf(read(bigTiff));
    assertEquals(DECLARED_LARGE_SIZE, declaredSize);
  }

  @Test
  void shouldRefuseANetpbmWidthThatGrowsPastTheLargestIntOnlyByItsLastDigit() {
    final byte[] largest = bytes("P6 2147483647 1\n");
    final byte[] tooLong = bytes("P6 21474836470 1\n");

    final String largestSize = sizeOf(read(largest));
    final Size tooLongSize = read(tooLong);
    assertEquals("2147483647x1", largestSize);
    assertNull(tooLongSize);
  }

  @Test
  void shouldReadOnlyTheTiffEntriesThatFitInTheDataWhole() {
    final byte[] header = bytes(TIFF_MAGIC_LITTLE_ENDIAN, littleEndian(CLASSIC_TIFF_VERSION, 2), littleEndian(0, 4), new byte[6]);
    final byte[] entries = bytes(
      tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_WIDTH_TAG, TIFF_SHORT_FIELD_TYPE, 1, LARGE_WIDTH),
      tiffEntry(TiffLayout.CLASSIC, TIFF_IMAGE_LENGTH_TAG, TIFF_LONG_FIELD_TYPE, 1, LARGE_HEIGHT)
    );
    final byte[] truncatedRepeat = littleEndian(TIFF_IMAGE_WIDTH_TAG, 2);
    final byte[] tiff = bytes(header, entries, truncatedRepeat);

    final String declaredSize = sizeOf(read(tiff));
    assertEquals(DECLARED_LARGE_SIZE, declaredSize);
  }

  @Test
  void shouldRefuseAnImageLargerThanTheLimitWithoutDecodingIt() {
    final byte[] header = bytes(
      PNG_SIGNATURE,
      bigEndian(PNG_HEADER_CHUNK_LENGTH, 4),
      PNG_HEADER_CHUNK,
      bigEndian(LARGE_WIDTH, 4),
      bigEndian(LARGE_HEIGHT, 4)
    );
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkBytes(header, 599_999_999)
    );
    final String message = refused.getMessage();
    assertEquals(
      "Image size 30000x20000 exceeds the limit of 599999999 pixels that the system property mcav.image.maximum_pixels sets!",
      message
    );
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(header, 600_000_000));

    final byte[] huge = bytes(
      PNG_SIGNATURE,
      bigEndian(PNG_HEADER_CHUNK_LENGTH, 4),
      PNG_HEADER_CHUNK,
      bigEndian(MAXIMUM_UNSIGNED_INTEGER, 4),
      bigEndian(MAXIMUM_UNSIGNED_INTEGER, 4)
    );
    assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkBytes(huge, Long.MAX_VALUE));
  }

  @Test
  void shouldLeaveEveryImageToOpenCvWithALimitOfZeroOrLess() throws IOException {
    final byte[] unknown = bytes(NOT_AN_IMAGE);
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown, 0));
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown, -1));

    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    assertDoesNotThrow(() -> DeclaredImageSize.checkFile(missing, 0));
  }

  @Test
  void shouldCheckFilesWithoutReadingThemWhole() throws IOException {
    final byte[] png = bytes(
      PNG_SIGNATURE,
      bigEndian(PNG_HEADER_CHUNK_LENGTH, 4),
      PNG_HEADER_CHUNK,
      bigEndian(16, 4),
      bigEndian(16, 4),
      new byte[1 << 20]
    );
    final Path file = this.directory.resolve(LARGE_FILE_NAME);
    Files.write(file, png);
    assertDoesNotThrow(() -> DeclaredImageSize.checkFile(file, 256));

    final IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(file, 255));
    final boolean namesTheSize = tooLarge.getMessage().startsWith("Image size 16x16 exceeds");
    assertTrue(namesTheSize, tooLarge.getMessage());

    final Path text = this.directory.resolve(TEXT_FILE_NAME);
    Files.writeString(text, NOT_AN_IMAGE);
    final IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(text, 256));
    final String unsupportedMessage = unsupported.getMessage();
    final String expectedUnsupportedMessage = UNSUPPORTED_FILE_MESSAGE.formatted(text);
    assertEquals(expectedUnsupportedMessage, unsupportedMessage);

    final Path empty = this.directory.resolve(EMPTY_FILE_NAME);
    Files.write(empty, new byte[0]);
    assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(empty, 256));

    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(missing, 256));
    final String absentMessage = absent.getMessage();
    final String expectedAbsentMessage = UNSUPPORTED_FILE_MESSAGE.formatted(missing);
    assertEquals(expectedAbsentMessage, absentMessage);

    final Throwable absentCause = absent.getCause();
    assertInstanceOf(NoSuchFileException.class, absentCause);

    final IllegalArgumentException folder = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkFile(this.directory, 256)
    );
    final Throwable folderCause = folder.getCause();
    assertInstanceOf(IOException.class, folderCause);
  }

  @Test
  void shouldReadTheLimitFromItsSystemProperty() {
    final String previous = System.getProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
    try {
      System.clearProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
      final long defaultLimit = DeclaredImageSize.readLimit();
      assertEquals(67_108_864L, defaultLimit);

      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "123");
      final long raisedLimit = DeclaredImageSize.readLimit();
      assertEquals(123L, raisedLimit);

      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "a lot");
      final long fallbackLimit = DeclaredImageSize.readLimit();
      assertEquals(67_108_864L, fallbackLimit);

      final byte[] unknown = bytes(NOT_AN_IMAGE);
      System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, "0");
      assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown));

      final Path missing = this.directory.resolve(MISSING_FILE_NAME);
      assertDoesNotThrow(() -> DeclaredImageSize.checkFile(missing));
    } finally {
      if (previous == null) {
        System.clearProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY);
      } else {
        System.setProperty(DeclaredImageSize.MAXIMUM_PIXELS_PROPERTY, previous);
      }
    }
  }

  @Test
  void shouldTreatOnlyCWhitespaceAsSpace() {
    for (final int code : new int[] { ' ', '\t', '\n', 0x0B, '\f', '\r' }) {
      final boolean space = DeclaredImageSize.isSpace(code);
      assertTrue(space, Integer.toString(code));
    }

    for (final int code : new int[] { -1, 0, 0x08, 0x0E, 0x1C, 'a', 0xA0 }) {
      final boolean space = DeclaredImageSize.isSpace(code);
      assertFalse(space, Integer.toString(code));
    }
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(DeclaredImageSize.class);
  }

  private enum TiffLayout {
    CLASSIC,
    BIG,
  }
}
