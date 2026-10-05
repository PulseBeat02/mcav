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

/**
 * Tests {@link DeclaredImageSize}: the size it reads must be the size OpenCV decodes, for every format OpenCV decodes,
 * so that a header declaring a huge size is refused before OpenCV allocates it.
 */
final class DeclaredImageSizeTest {

  private static final String SEEDS = "/me/brandonli/mcav/media/image/ImageDecodeFuzzTestInputs/decodesAnImageOrRefusesTheBytes";

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

  private static byte[] le(final long value, final int count) {
    final byte[] result = new byte[count];
    for (int index = 0; index < count; index++) {
      result[index] = (byte) (value >>> (8 * index));
    }
    return result;
  }

  private static byte[] be(final long value, final int count) {
    final byte[] result = new byte[count];
    for (int index = 0; index < count; index++) {
      result[count - 1 - index] = (byte) (value >>> (8 * index));
    }
    return result;
  }

  private static @Nullable Size read(final byte[] encoded) {
    return DeclaredImageSize.read(MemorySegment.ofArray(encoded));
  }

  private static String sizeOf(final @Nullable Size size) {
    return size == null ? "none" : size.width() + "x" + size.height();
  }

  // an uncompressed 3 by 2 RGB TIFF, classic or BigTIFF, in either byte order
  private static byte[] tiff(final boolean little, final boolean big) {
    final int entrySize = big ? 20 : 12;
    final int countSize = big ? 8 : 2;
    final int directory = big ? 16 : 8;
    final int entries = 8;
    final int pixels = directory + countSize + entries * entrySize + (big ? 8 : 4);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(little ? "II".getBytes(StandardCharsets.ISO_8859_1) : "MM".getBytes(StandardCharsets.ISO_8859_1));
    output.writeBytes(number(big ? 43 : 42, 2, little));
    if (big) {
      output.writeBytes(number(8, 2, little));
      output.writeBytes(number(0, 2, little));
      output.writeBytes(number(directory, 8, little));
    } else {
      output.writeBytes(number(directory, 4, little));
    }
    output.writeBytes(number(entries, countSize, little));
    final long[][] tags = {
      { 256, 3, 1, 3 },
      { 257, 3, 1, 2 },
      { 258, 3, 1, 8 },
      { 262, 3, 1, 2 },
      { 273, 4, 1, pixels },
      { 277, 3, 1, 3 },
      { 278, 3, 1, 2 },
      { 279, 4, 1, 18 },
    };
    for (final long[] tag : tags) {
      output.writeBytes(number(tag[0], 2, little));
      output.writeBytes(number(tag[1], 2, little));
      output.writeBytes(number(tag[2], big ? 8 : 4, little));
      final int width = tag[1] == 3 ? 2 : 4;
      output.writeBytes(number(tag[3], width, little));
      output.writeBytes(new byte[(big ? 8 : 4) - width]);
    }
    output.writeBytes(new byte[big ? 8 : 4]);
    for (int index = 0; index < 18; index++) {
      output.write(index * 13);
    }
    return output.toByteArray();
  }

  private static byte[] number(final long value, final int count, final boolean little) {
    return little ? le(value, count) : be(value, count);
  }

  private static Size decodedByOpenCv(final byte[] encoded) {
    try (final BytePointer pointer = new BytePointer(encoded); final Mat wrapper = new Mat(pointer)) {
      final Mat decoded = opencv_imgcodecs.imdecode(wrapper, opencv_imgcodecs.IMREAD_COLOR);
      try {
        assertFalse(decoded.empty(), "OpenCV decodes the sample");
        return new Size(decoded.cols(), decoded.rows());
      } finally {
        decoded.release();
      }
    }
  }

  private static void assertDeclaresWhatOpenCvDecodes(final String name, final byte[] encoded) {
    final Size decoded = decodedByOpenCv(encoded);
    final Size declared = read(encoded);
    assertEquals(sizeOf(decoded), sizeOf(declared), name);
    final long pixels = decoded.width() * decoded.height();
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(encoded, pixels), name);
    // a limit of 0 turns the check off, so a single pixel has no limit below it to refuse it
    final long below = Math.max(1, pixels - 1);
    final boolean refusedBelow = pixels > 1;
    assertEquals(refusedBelow, refuses(encoded, below), name);
  }

  private static boolean refuses(final byte[] encoded, final long limit) {
    try {
      DeclaredImageSize.checkBytes(encoded, limit);
      return false;
    } catch (final IllegalArgumentException refused) {
      return true;
    }
  }

  static Stream<Arguments> openCvSamples() {
    final List<String> extensions = List.of(
      ".bmp",
      ".jpg",
      ".png",
      ".webp",
      ".ppm",
      ".pgm",
      ".pnm",
      ".pam",
      ".pfm",
      ".sr",
      ".tif",
      ".hdr",
      ".gif"
    );
    final int[][] sizes = { { 5, 7 }, { 300, 2 }, { 1, 1 } };
    return extensions
      .stream()
      .flatMap(extension ->
        Stream.of(sizes).flatMap(size ->
          Stream.of(opencv_core.CV_8UC1, opencv_core.CV_8UC3).map(type -> Arguments.of(extension, size[0], size[1], type))
        )
      );
  }

  @ParameterizedTest
  @MethodSource("openCvSamples")
  void everyFormatOpenCvWritesDeclaresTheSizeOpenCvDecodes(final String extension, final int width, final int height, final int type) {
    final boolean gray = type == opencv_core.CV_8UC1;
    final boolean colorOnly = extension.equals(".ppm") || extension.equals(".gif");
    final boolean grayOnly = extension.equals(".pgm");
    if ((gray && colorOnly) || (!gray && grayOnly)) {
      return;
    }
    try (final Mat picture = new Mat(height, width, type, new Scalar(10, 200, 30, 0)); final BytePointer output = new BytePointer()) {
      final boolean written = opencv_imgcodecs.imencode(extension, picture, output);
      assertTrue(written, extension);
      final byte[] encoded = new byte[(int) output.limit()];
      output.get(encoded);
      assertDeclaresWhatOpenCvDecodes(extension + " " + width + "x" + height, encoded);
    }
  }

  @Test
  void losslessWebpAndTextNetpbmDeclareTheSizeOpenCvDecodes() {
    try (final Mat picture = new Mat(7, 5, opencv_core.CV_8UC1, new Scalar(90)); final BytePointer output = new BytePointer()) {
      try (final IntPointer lossless = new IntPointer(opencv_imgcodecs.IMWRITE_WEBP_QUALITY, 101)) {
        assertTrue(opencv_imgcodecs.imencode(".webp", picture, output, lossless));
      }
      final byte[] webp = new byte[(int) output.limit()];
      output.get(webp);
      assertDeclaresWhatOpenCvDecodes("lossless webp", webp);
      try (final IntPointer text = new IntPointer(opencv_imgcodecs.IMWRITE_PXM_BINARY, 0)) {
        assertTrue(opencv_imgcodecs.imencode(".pgm", picture, output, text));
      }
      final byte[] pgm = new byte[(int) output.limit()];
      output.get(pgm);
      assertDeclaresWhatOpenCvDecodes("text pgm", pgm);
    }
  }

  @Test
  void theFuzzSeedsDeclareTheSizeOpenCvDecodes() throws IOException, URISyntaxException {
    final URL seeds = DeclaredImageSizeTest.class.getResource(SEEDS);
    assertTrue(seeds != null, "the seed folder is on the class path");
    final Path folder = Path.of(seeds.toURI());
    try (final Stream<Path> files = Files.list(folder)) {
      final List<Path> samples = files.sorted().toList();
      assertEquals(10, samples.size());
      for (final Path sample : samples) {
        assertDeclaresWhatOpenCvDecodes(sample.getFileName().toString(), Files.readAllBytes(sample));
      }
    }
  }

  @Test
  void filesImageIoWritesDeclareTheSizeOpenCvDecodes() throws IOException {
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
    assertTrue(bmp[25] < 0, "the height of a top-down BMP is negative");
    assertDeclaresWhatOpenCvDecodes("top-down bmp", bmp);
  }

  @Test
  void tiffsOfBothByteOrdersAndBigTiffsDeclareTheSizeOpenCvDecodes() {
    assertDeclaresWhatOpenCvDecodes("little-endian tiff", tiff(true, false));
    assertDeclaresWhatOpenCvDecodes("big-endian tiff", tiff(false, false));
    assertDeclaresWhatOpenCvDecodes("little-endian bigtiff", tiff(true, true));
    assertDeclaresWhatOpenCvDecodes("big-endian bigtiff", tiff(false, true));
  }

  private static byte[] tiffEntry(final boolean big, final long tag, final long type, final long count, final long value) {
    final int width = type == 3 ? 2 : type == 16 ? 8 : 4;
    final int field = big ? 8 : 4;
    return bytes(le(tag, 2), le(type, 2), le(count, big ? 8 : 4), le(value, width), new byte[Math.max(0, field - width)]);
  }

  private static byte[] classicTiff(final byte[]... entries) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(bytes("II", le(42, 2), le(8, 4), le(entries.length, 2)));
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
    return le((width - 1) | ((long) (height - 1) << 14) | ((long) version << 29), 4);
  }

  static Stream<Arguments> declaredSizes() {
    return Stream.of(
      Arguments.of("bmp", bytes("BM", new byte[12], le(40, 4), le(30000, 4), le(20000, 4)), 30000, 20000),
      Arguments.of("top-down bmp", bytes("BM", new byte[12], le(124, 4), le(30000, 4), le(-20000, 4)), 30000, 20000),
      Arguments.of("os/2 bmp", bytes("BM", new byte[12], le(12, 4), le(65535, 2), le(300, 2)), 65535, 300),
      // OpenCV refuses the least height outright; read as its magnitude, it is refused by any limit too
      Arguments.of("bmp of the least height", bytes("BM", new byte[12], le(40, 4), le(5, 4), le(Integer.MIN_VALUE, 4)), 5, 2147483648L),
      Arguments.of("gif89a", bytes("GIF89a", le(65535, 2), le(32768, 2)), 65535, 32768),
      Arguments.of("gif87a", bytes("GIF87a", le(5, 2), le(7, 2)), 5, 7),
      Arguments.of("radiance", bytes("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 20000 +X 30000\n"), 30000, 20000),
      Arguments.of("rgbe with a comment and signs", bytes("#?RGBE\n# made by hand\n\n-Y\t+7 +X 5\n"), 5, 7),
      Arguments.of("hdr line longer than a piece", bytes("#?RGBE\n#", "x".repeat(200), "\n\n-Y 2 +X 3\n"), 3, 2),
      // OpenCV reads a 127-character line and its line feed as two pieces, the second of which ends the header
      Arguments.of("hdr piece ending the header", bytes("#?RGBE\n", "F".repeat(127), "\n-Y 4 +X 6\n"), 6, 4),
      Arguments.of(
        "jpeg",
        bytes(0xFF, 0xD8, 0xFF, 0xE0, be(16, 2), new byte[14], 0xFF, 0xC0, be(17, 2), 8, be(20000, 2), be(30000, 2)),
        30000,
        20000
      ),
      Arguments.of(
        "jpeg with garbage, fill bytes and standalone markers",
        bytes(0xFF, 0xD8, 0xFF, 0x01, 0x00, 0x12, 0xFF, 0xFF, 0xD0, 0xFF, 0xD7, 0xFF, 0xFF, 0xC2, be(11, 2), 8, be(7, 2), be(5, 2)),
        5,
        7
      ),
      Arguments.of(
        "jpeg after tables with lengths",
        bytes(
          0xFF,
          0xD8,
          0xFF,
          0xC4,
          be(3, 2),
          0,
          0xFF,
          0xC8,
          be(2, 2),
          0xFF,
          0xCC,
          be(4, 2),
          0,
          0,
          0xFF,
          0xB0,
          be(2, 2),
          0xFF,
          0xE1,
          be(2, 2),
          0xFF,
          0xC1,
          be(11, 2),
          8,
          be(9, 2),
          be(4, 2)
        ),
        4,
        9
      ),
      Arguments.of(
        "extended webp",
        pad(bytes("RIFF", le(30, 4), "WEBP", "VP8X", le(10, 4), le(16, 4), le(16383, 3), le(9999, 3))),
        16384,
        10000
      ),
      Arguments.of("lossless webp", pad(bytes("RIFF", le(30, 4), "WEBP", "VP8L", le(5, 4), 0x2F, lossless(300, 200, 0))), 300, 200),
      Arguments.of("raw lossless webp", pad(bytes(0x2F, lossless(16384, 16384, 0))), 16384, 16384),
      Arguments.of(
        "lossy webp",
        pad(
          bytes(
            "RIFF",
            le(30, 4),
            "WEBP",
            "VP8 ",
            le(10, 4),
            0x10,
            0x02,
            0x00,
            0x9D,
            0x01,
            0x2A,
            le(0xC000 | 16383, 2),
            le(0x4000 | 300, 2)
          )
        ),
        16383,
        300
      ),
      Arguments.of("sun raster", bytes(0x59, 0xA6, 0x6A, 0x95, be(30000, 4), be(20000, 4)), 30000, 20000),
      Arguments.of("ppm", bytes("P6\n30000 20000\n255\n"), 30000, 20000),
      Arguments.of("pbm with comments", bytes("P1 # made by hand\r5\t\n 7\n"), 5, 7),
      Arguments.of("pbm with comments on their own lines", bytes("P4\n#one\n#two\n3 4"), 3, 4),
      Arguments.of(
        "pam",
        bytes(
          "P7\n# made by hand\r\n\nWIDTH 30000\n  HEIGHT   20000  \nDEPTH 3\nMAXVAL 255\nTUPLTYPE RGB\n#",
          "c".repeat(600),
          "\nENDHDR\n"
        ),
        30000,
        20000
      ),
      Arguments.of("pfm", bytes("PF\n30000 20000\n-1.0\n"), 30000, 20000),
      Arguments.of("pfm read like atoi", bytes("Pf\n+5 7abc\n1\n"), 5, 7),
      Arguments.of(
        "tiff",
        classicTiff(tiffEntry(false, 254, 4, 1, 0), tiffEntry(false, 256, 3, 1, 30000), tiffEntry(false, 257, 4, 1, 20000)),
        30000,
        20000
      ),
      Arguments.of("png", bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IHDR", be(4294967295L, 4), be(1, 4)), 4294967295L, 1)
    );
  }

  @ParameterizedTest
  @MethodSource("declaredSizes")
  void readsTheSizeTheHeaderDeclares(final String name, final byte[] encoded, final long width, final long height) {
    final Size size = read(encoded);
    assertEquals(width + "x" + height, sizeOf(size), name);
  }

  static Stream<Arguments> unreadableHeaders() {
    return Stream.of(
      Arguments.of("nothing", new byte[0]),
      Arguments.of("unknown bytes", bytes("not an image at all, not one")),
      Arguments.of("jpeg 2000", bytes(0, 0, 0, 0x0C, "jP  \r\n", 0x87, "\n", new byte[20])),
      Arguments.of("openexr", bytes(0x76, 0x2F, 0x31, 0x01, new byte[40])),
      Arguments.of("bmp with an unknown info header", bytes("BM", new byte[12], le(20, 4), le(5, 4), le(7, 4))),
      Arguments.of("truncated bmp", bytes("BM", new byte[4])),
      Arguments.of("bmp without width", bytes("BM", new byte[12], le(40, 4), le(0, 4), le(7, 4))),
      Arguments.of("gif of another version", bytes("GIF88a", le(5, 2), le(7, 2))),
      Arguments.of("hdr without the end of its header", bytes("#?RGBE\nFORMAT=32-bit_rle_rgbe\n")),
      Arguments.of("hdr without its resolution", bytes("#?RGBE\n\n")),
      Arguments.of("hdr with another orientation", bytes("#?RGBE\n\n+Y 7 +X 5\n")),
      Arguments.of("hdr without its x", bytes("#?RGBE\n\n-Y 7 X 5\n")),
      Arguments.of("hdr without its width", bytes("#?RGBE\n\n-Y 7 +X\n")),
      Arguments.of("hdr with a huge number", bytes("#?RGBE\n\n-Y 99999999999 +X 5\n")),
      Arguments.of("hdr with a negative height", bytes("#?RGBE\n\n-Y -7 +X 5\n")),
      Arguments.of("hdr cut by a nul", bytes("#?RGBE\n\n-Y 7", 0, " +X 5\n")),
      Arguments.of("jpeg ending before a frame", bytes(0xFF, 0xD8, 0xFF, 0xE0, be(4, 2), 0, 0, 0xFF, 0xD9)),
      Arguments.of("jpeg scan before a frame", bytes(0xFF, 0xD8, 0xFF, 0xDA, be(2, 2))),
      Arguments.of("jpeg starting twice", bytes(0xFF, 0xD8, 0xFF, 0xD8)),
      Arguments.of("jpeg without markers", bytes(0xFF, 0xD8, 0xFF)),
      Arguments.of("jpeg running into garbage", bytes(0xFF, 0xD8, 0xFF, 0xE0, be(4, 2), 0, 0, 0x12, 0x34)),
      Arguments.of("jpeg with a short length", bytes(0xFF, 0xD8, 0xFF, 0xE0, be(1, 2))),
      Arguments.of("jpeg without a length", bytes(0xFF, 0xD8, 0xFF, 0xE0)),
      Arguments.of("jpeg frame without its size", bytes(0xFF, 0xD8, 0xFF, 0xC0, be(17, 2), 8)),
      Arguments.of("webp shorter than its signature", bytes("RIFF", le(30, 4), "WEBP", "VP8X", le(10, 4), le(0, 4), le(5, 3), le(5, 3))),
      Arguments.of("riff of another kind", pad(bytes("RIFF", le(30, 4), "WAVE", "fmt "))),
      Arguments.of(
        "webp with a broken extended header",
        pad(bytes("RIFF", le(30, 4), "WEBP", "VP8X", le(9, 4), le(0, 4), le(5, 3), le(5, 3)))
      ),
      Arguments.of("lossless webp of another version", pad(bytes("RIFF", le(30, 4), "WEBP", "VP8L", le(5, 4), 0x2F, lossless(5, 7, 1)))),
      Arguments.of("lossless webp without its signature", pad(bytes("RIFF", le(30, 4), "WEBP", "VP8L", le(5, 4), 0x2E, lossless(5, 7, 0)))),
      Arguments.of(
        "webp with another first chunk",
        pad(bytes("RIFF", le(30, 4), "WEBP", "ALPH", le(10, 4), 0x10, 0x02, 0x00, 0x9D, 0x01, 0x2A))
      ),
      Arguments.of(
        "lossy webp of a later frame",
        pad(bytes("RIFF", le(30, 4), "WEBP", "VP8 ", le(10, 4), 0x11, 0x02, 0x00, 0x9D, 0x01, 0x2A))
      ),
      Arguments.of(
        "lossy webp without its start code",
        pad(bytes("RIFF", le(30, 4), "WEBP", "VP8 ", le(10, 4), 0x10, 0x02, 0x00, 0x9D, 0x01, 0x2B))
      ),
      Arguments.of(
        "lossy webp of no width",
        pad(bytes("RIFF", le(30, 4), "WEBP", "VP8 ", le(10, 4), 0x10, 0x02, 0x00, 0x9D, 0x01, 0x2A, le(0xC000, 2)))
      ),
      Arguments.of("sun raster of a negative width", bytes(0x59, 0xA6, 0x6A, 0x95, be(0x80000000L, 4), be(5, 4))),
      Arguments.of("truncated sun raster", bytes(0x59, 0xA6, 0x6A, 0x95, be(5, 4))),
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
      Arguments.of("pam with a long value", bytes("P7\nTUPLTYPE ", "x".repeat(600), "\nWIDTH 5\nHEIGHT 7\nENDHDR\n")),
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
      Arguments.of("tiff of another magic", bytes("II", le(44, 2), le(8, 4), le(0, 2))),
      Arguments.of("tiff with its directory past the end", bytes("II", le(42, 2), le(4000, 4))),
      Arguments.of("bigtiff with its directory past any file", bytes("II", le(43, 2), le(8, 2), le(0, 2), le(-1, 8))),
      Arguments.of(
        "tiff with two widths",
        classicTiff(tiffEntry(false, 256, 3, 1, 5), tiffEntry(false, 256, 3, 1, 9), tiffEntry(false, 257, 3, 1, 7))
      ),
      Arguments.of(
        "tiff with two lengths",
        classicTiff(tiffEntry(false, 256, 3, 1, 5), tiffEntry(false, 257, 3, 1, 9), tiffEntry(false, 257, 3, 1, 7))
      ),
      Arguments.of("tiff with a width of two values", classicTiff(tiffEntry(false, 256, 3, 2, 5), tiffEntry(false, 257, 3, 1, 7))),
      Arguments.of("tiff with a width in bytes", classicTiff(tiffEntry(false, 256, 1, 1, 5), tiffEntry(false, 257, 3, 1, 7))),
      Arguments.of("classic tiff with an eight-byte width", classicTiff(tiffEntry(false, 256, 16, 1, 5), tiffEntry(false, 257, 3, 1, 7))),
      Arguments.of("tiff without its length", classicTiff(tiffEntry(false, 256, 3, 1, 5))),
      Arguments.of("png without its header chunk", bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IDAT", be(5, 4), be(7, 4))),
      Arguments.of("png of no height", bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IHDR", be(5, 4), be(0, 4)))
    );
  }

  @ParameterizedTest
  @MethodSource("unreadableHeaders")
  void refusesBytesWhoseHeaderItCannotRead(final String name, final byte[] encoded) {
    assertNull(read(encoded), name);
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkBytes(encoded, 1L << 40)
    );
    assertEquals("Bytes are not a supported image format", refused.getMessage(), name);
  }

  @Test
  void readsAnEightByteWidthOfABigTiff() {
    final byte[] entries = bytes(tiffEntry(true, 256, 16, 1, 30000), tiffEntry(true, 257, 4, 1, 20000));
    final byte[] bigTiff = bytes("II", le(43, 2), le(8, 2), le(0, 2), le(16, 8), le(2, 8), entries);
    assertEquals("30000x20000", sizeOf(read(bigTiff)));
  }

  @Test
  void refusesAnImageLargerThanTheLimitWithoutDecodingIt() {
    final byte[] header = bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IHDR", be(30000, 4), be(20000, 4));
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkBytes(header, 599_999_999)
    );
    assertEquals(
      "Image declares 30000x20000 pixels, more than the 599999999 pixels mcav decodes (the system property mcav.image.maxPixels raises the limit)",
      refused.getMessage()
    );
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(header, 600_000_000));
    // a product that does not fit a long is still compared without overflowing
    final byte[] huge = bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IHDR", be(4294967295L, 4), be(4294967295L, 4));
    assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkBytes(huge, Long.MAX_VALUE));
  }

  @Test
  void aLimitOfZeroOrLessLeavesEveryImageToOpenCv() throws IOException {
    final byte[] unknown = bytes("not an image");
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown, 0));
    assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown, -1));
    final Path missing = this.directory.resolve("missing.png");
    assertDoesNotThrow(() -> DeclaredImageSize.checkFile(missing, 0));
  }

  @Test
  void checksFilesWithoutReadingThemWhole() throws IOException {
    final byte[] png = bytes(0x89, "PNG\r\n", 0x1A, "\n", be(13, 4), "IHDR", be(16, 4), be(16, 4), new byte[1 << 20]);
    final Path file = this.directory.resolve("large.png");
    Files.write(file, png);
    assertDoesNotThrow(() -> DeclaredImageSize.checkFile(file, 256));
    final IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(file, 255));
    assertTrue(tooLarge.getMessage().startsWith("Image declares 16x16 pixels"), tooLarge.getMessage());
    final Path text = this.directory.resolve("text.png");
    Files.writeString(text, "not an image");
    final IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(text, 256));
    assertEquals("File is not a supported image: " + text, unsupported.getMessage());
    final Path empty = this.directory.resolve("empty.png");
    Files.write(empty, new byte[0]);
    assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(empty, 256));
    final Path missing = this.directory.resolve("missing.png");
    final IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () -> DeclaredImageSize.checkFile(missing, 256));
    assertEquals("File is not a supported image: " + missing, absent.getMessage());
    assertInstanceOf(NoSuchFileException.class, absent.getCause());
    final IllegalArgumentException folder = assertThrows(IllegalArgumentException.class, () ->
      DeclaredImageSize.checkFile(this.directory, 256)
    );
    assertInstanceOf(IOException.class, folder.getCause());
  }

  @Test
  void readsTheLimitFromItsSystemProperty() {
    final String previous = System.getProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY);
    try {
      System.clearProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY);
      assertEquals(67_108_864L, DeclaredImageSize.limit());
      System.setProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY, "123");
      assertEquals(123L, DeclaredImageSize.limit());
      System.setProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY, "a lot");
      assertEquals(67_108_864L, DeclaredImageSize.limit());
      final byte[] unknown = bytes("not an image");
      System.setProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY, "0");
      assertDoesNotThrow(() -> DeclaredImageSize.checkBytes(unknown));
      final Path missing = this.directory.resolve("missing.png");
      assertDoesNotThrow(() -> DeclaredImageSize.checkFile(missing));
    } finally {
      if (previous == null) {
        System.clearProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY);
      } else {
        System.setProperty(DeclaredImageSize.MAX_PIXELS_PROPERTY, previous);
      }
    }
  }

  @Test
  void treatsOnlyCWhitespaceAsSpace() {
    for (final int code : new int[] { ' ', '\t', '\n', 0x0B, '\f', '\r' }) {
      assertTrue(DeclaredImageSize.isSpace(code), Integer.toString(code));
    }
    for (final int code : new int[] { -1, 0, 0x08, 0x0E, 0x1C, 'a', 0xA0 }) {
      assertFalse(DeclaredImageSize.isSpace(code), Integer.toString(code));
    }
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(DeclaredImageSize.class);
  }
}
