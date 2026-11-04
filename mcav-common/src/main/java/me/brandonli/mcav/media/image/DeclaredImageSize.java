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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

// Refuses an encoded image whose header declares more pixels than the limit, before OpenCV decodes it. OpenCV allocates
// the whole picture as soon as it has read the header, so a small file that declares a huge size (a decompression bomb)
// would take gigabytes of native memory before anything could look at the result. The header is therefore read here
// first, the way OpenCV's own decoder for the format reads it: BMP, GIF, Radiance HDR, JPEG, WebP, Sun raster, the
// Netpbm formats (PBM, PGM, PPM, PAM, PFM), TIFF and PNG, which are the formats the bundled OpenCV decodes. Bytes in
// any other format are refused as unsupported, as OpenCV refuses them too: JPEG 2000 and OpenEXR, which OpenCV disables
// unless an environment variable turns them on, are refused as well. The limit is the system property
// MAXIMUM_PIXELS_PROPERTY, DEFAULT_MAXIMUM_PIXELS pixels (8192 by 8192) if it is not set. A value of 0 or less turns
// the check off and leaves every image to OpenCV, as before the check.
final class DeclaredImageSize {

  static final String MAXIMUM_PIXELS_PROPERTY = "mcav.image.maxPixels";

  private static final long DEFAULT_MAXIMUM_PIXELS = 1L << 26;

  private static final String UNSUPPORTED_FILE = "File %s is not a supported image!";

  // OpenCV reads its header lines in pieces of at most 127 characters, the way fgets does with a 128-byte buffer
  private static final int MAXIMUM_HDR_PIECE_LENGTH = 127;
  // OpenCV's PFM reader reads a number of at most 2048 characters
  private static final int MAXIMUM_PFM_NUMBER_LENGTH = 2048;
  // OpenCV's PAM reader takes a name of at most 8 characters and a value of at most 255
  private static final int MAXIMUM_PAM_LINE_LENGTH = 512;
  private static final int MINIMUM_WEBP_HEADER_LENGTH = 32;

  private static final int SIXTEEN_BIT_BYTES = 2;
  private static final int TWENTY_FOUR_BIT_BYTES = 3;
  private static final int THIRTY_TWO_BIT_BYTES = 4;
  private static final int SIXTY_FOUR_BIT_BYTES = 8;
  private static final int BITS_PER_BYTE = 8;

  private static final int DECIMAL_BASE = 10;
  private static final int MAXIMUM_DECIMAL_DIGITS = 10;
  private static final char FIRST_DIGIT = '0';
  private static final char LAST_DIGIT = '9';
  private static final String PLUS_SIGN = "+";
  private static final String MINUS_SIGN = "-";

  private static final char SPACE = ' ';
  private static final char HORIZONTAL_TAB = '\t';
  private static final char LINE_FEED = '\n';
  private static final char CARRIAGE_RETURN = '\r';

  private static final char STRING_TERMINATOR = '\0';

  private static final String COMMENT_START = "#";
  private static final char COMMENT_START_CODE = '#';

  private static final long MISSING = -1;
  // a size that is given twice or in a form the decoder refuses
  private static final long REFUSED = -2;

  private static final int END_OF_DATA = -1;

  private static final String BMP_SIGNATURE = "BM";
  private static final int BMP_INFORMATION_SIZE_OFFSET = 14;
  private static final int BMP_MINIMUM_INFORMATION_SIZE = 36;
  private static final int BMP_WIDTH_OFFSET = 18;
  private static final int BMP_HEIGHT_OFFSET = 22;
  private static final int OS2_INFORMATION_SIZE = 12;
  private static final int OS2_WIDTH_OFFSET = 18;
  private static final int OS2_HEIGHT_OFFSET = 20;

  private static final String GIF_SIGNATURE = "GIF";
  private static final String GIF87_SIGNATURE = "GIF87a";
  private static final String GIF89_SIGNATURE = "GIF89a";
  private static final int GIF_WIDTH_OFFSET = 6;
  private static final int GIF_HEIGHT_OFFSET = 8;

  private static final String HDR_RGBE_SIGNATURE = "#?RGBE";
  private static final String HDR_RADIANCE_SIGNATURE = "#?RADIANCE";
  private static final String HDR_HEIGHT_AXIS = "-Y";
  private static final String HDR_WIDTH_AXIS = "+X";

  private static final String JPEG_SIGNATURE = "\u00FF\u00D8\u00FF";
  private static final int JPEG_FIRST_MARKER_OFFSET = 2;
  private static final int JPEG_MARKER_PREFIX = 0xFF;
  private static final int JPEG_FIRST_FRAME = 0xC0;
  private static final int JPEG_LAST_FRAME = 0xCF;
  private static final int JPEG_HUFFMAN_TABLE = 0xC4;
  private static final int JPEG_EXTENSION = 0xC8;
  private static final int JPEG_ARITHMETIC_CODING = 0xCC;
  private static final int JPEG_STUFFED_ZERO = 0x00;
  private static final int JPEG_TEMPORARY = 0x01;
  private static final int JPEG_FIRST_RESTART = 0xD0;
  private static final int JPEG_LAST_RESTART = 0xD7;
  private static final int JPEG_START_OF_IMAGE = 0xD8;
  private static final int JPEG_END_OF_IMAGE = 0xD9;
  private static final int JPEG_START_OF_SCAN = 0xDA;
  private static final int JPEG_FRAME_HEIGHT_OFFSET = 3;
  private static final int JPEG_FRAME_WIDTH_OFFSET = 5;

  private static final String SUN_RASTER_SIGNATURE = "Y\u00A6j\u0095";
  private static final int SUN_RASTER_WIDTH_OFFSET = 4;
  private static final int SUN_RASTER_HEIGHT_OFFSET = 8;

  private static final char NETPBM_SIGNATURE = 'P';
  private static final int NETPBM_KIND_OFFSET = 1;
  private static final int NETPBM_SEPARATOR_OFFSET = 2;
  private static final char PNM_FIRST_KIND = '1';
  private static final char PNM_LAST_KIND = '6';
  private static final char PAM_KIND = '7';
  private static final char PFM_GRAY_KIND = 'f';
  private static final char PFM_COLOR_KIND = 'F';
  private static final int PNM_HEADER_OFFSET = 2;
  private static final int PAM_HEADER_OFFSET = 3;
  private static final int PFM_HEADER_OFFSET = 3;
  private static final Pattern PAM_SEPARATOR = Pattern.compile("\\s+");
  private static final int PAM_LINE_PARTS = 2;
  private static final String PAM_END = "ENDHDR";
  private static final String PAM_WIDTH = "WIDTH";
  private static final String PAM_HEIGHT = "HEIGHT";
  private static final String PAM_DEPTH = "DEPTH";
  private static final String PAM_MAXIMUM_VALUE = "MAXVAL";
  private static final String PAM_TUPLE_TYPE = "TUPLTYPE";
  private static final Set<String> PAM_FIELDS = Set.of(PAM_WIDTH, PAM_HEIGHT, PAM_DEPTH, PAM_MAXIMUM_VALUE, PAM_TUPLE_TYPE);

  private static final String TIFF_LITTLE_ENDIAN_SIGNATURE = "II";
  private static final String TIFF_BIG_ENDIAN_SIGNATURE = "MM";
  private static final int TIFF_MAGIC_OFFSET = 2;
  private static final int TIFF_MAGIC = 42;
  private static final int BIG_TIFF_MAGIC = 43;
  private static final int TIFF_DIRECTORY_OFFSET = 4;
  private static final int BIG_TIFF_DIRECTORY_OFFSET = 8;
  private static final int TIFF_ENTRY_SIZE = 12;
  private static final int BIG_TIFF_ENTRY_SIZE = 20;
  private static final int TIFF_IMAGE_WIDTH_TAG = 256;
  private static final int TIFF_IMAGE_LENGTH_TAG = 257;
  private static final int TIFF_TYPE_OFFSET = 2;
  private static final int TIFF_COUNT_OFFSET = 4;
  private static final int TIFF_VALUE_OFFSET = 8;
  private static final int BIG_TIFF_VALUE_OFFSET = 12;
  private static final int TIFF_SHORT_TYPE = 3;
  private static final int TIFF_LONG_TYPE = 4;
  private static final int TIFF_LONG8_TYPE = 16;

  private static final String PNG_SIGNATURE = "\u0089PNG\r\n\u001A\n";
  private static final String PNG_HEADER_CHUNK = "IHDR";
  private static final int PNG_HEADER_CHUNK_OFFSET = 12;
  private static final int PNG_WIDTH_OFFSET = 16;
  private static final int PNG_HEIGHT_OFFSET = 20;

  private static final String RIFF_SIGNATURE = "RIFF";
  private static final String WEBP_FORMAT = "WEBP";
  private static final int WEBP_FORMAT_OFFSET = 8;
  private static final int WEBP_CHUNK_OFFSET = 12;
  private static final String VP8X_CHUNK = "VP8X";
  private static final int VP8X_CHUNK_SIZE_OFFSET = 16;
  private static final int VP8X_CHUNK_SIZE = 10;
  private static final int VP8X_WIDTH_OFFSET = 24;
  private static final int VP8X_HEIGHT_OFFSET = 27;
  private static final String VP8L_CHUNK = "VP8L";
  private static final int VP8L_BITSTREAM_OFFSET = 20;
  private static final int VP8L_SIGNATURE = 0x2F;
  private static final int VP8L_VERSION_SHIFT = 29;
  private static final int VP8L_HEIGHT_SHIFT = 14;
  private static final String VP8_CHUNK = "VP8 ";
  private static final int VP8_FRAME_TAG_OFFSET = 20;
  private static final int VP8_KEY_FRAME_BIT = 1;
  private static final String VP8_START_CODE = "\u009D\u0001*";
  private static final int VP8_START_CODE_OFFSET = 23;
  private static final int VP8_WIDTH_OFFSET = 26;
  private static final int VP8_HEIGHT_OFFSET = 28;
  private static final int VP8_DIMENSION_MASK = 0x3FFF;

  private DeclaredImageSize() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void checkBytes(final byte[] encoded) {
    checkBytes(encoded, readLimit());
  }

  static void checkBytes(final byte[] encoded, final long limit) {
    if (limit <= 0) {
      return;
    }

    final MemorySegment segment = MemorySegment.ofArray(encoded);
    final Size size = readDeclaredSize(segment);
    if (size == null) {
      final String message = "Bytes are not a supported image format!";
      throw new IllegalArgumentException(message);
    }

    checkLimit(size, limit);
  }

  // Checks the header of an image file, mapping the file instead of reading it whole.
  static void checkFile(final Path path) {
    checkFile(path, readLimit());
  }

  static void checkFile(final Path path, final long limit) {
    if (limit <= 0) {
      return;
    }

    final Size size;
    try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ); final Arena arena = Arena.ofConfined()) {
      final long length = channel.size();
      final MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, length, arena);
      size = readDeclaredSize(segment);
    } catch (final IOException exception) {
      final String message = UNSUPPORTED_FILE.formatted(path);
      throw new IllegalArgumentException(message, exception);
    }

    if (size == null) {
      final String message = UNSUPPORTED_FILE.formatted(path);
      throw new IllegalArgumentException(message);
    }

    checkLimit(size, limit);
  }

  static long readLimit() {
    return Long.getLong(MAXIMUM_PIXELS_PROPERTY, DEFAULT_MAXIMUM_PIXELS);
  }

  private static void checkLimit(final Size size, final long limit) {
    final long width = size.width();
    final long height = size.height();
    // Test width * height > limit without overflowing the product.
    final boolean tooLarge = width > limit / height;
    if (tooLarge) {
      final String message = "Image size %sx%s exceeds the limit of %s pixels that the system property %s sets!".formatted(
        width,
        height,
        limit,
        MAXIMUM_PIXELS_PROPERTY
      );
      throw new IllegalArgumentException(message);
    }
  }

  static @Nullable Size readDeclaredSize(final MemorySegment segment) {
    final Header header = new Header(segment);
    if (header.startsWith(0, BMP_SIGNATURE)) {
      return readBmpSize(header);
    }

    if (header.startsWith(0, GIF_SIGNATURE)) {
      return readGifSize(header);
    }

    if (header.startsWith(0, HDR_RGBE_SIGNATURE) || header.startsWith(0, HDR_RADIANCE_SIGNATURE)) {
      return readHdrSize(header);
    }

    if (header.startsWith(0, JPEG_SIGNATURE)) {
      return readJpegSize(header);
    }

    final Size webp = readWebpSize(header);
    if (webp != null) {
      return webp;
    }

    if (header.startsWith(0, SUN_RASTER_SIGNATURE)) {
      return createPositiveSize(
        header.readSignedBigEndian32(SUN_RASTER_WIDTH_OFFSET),
        header.readSignedBigEndian32(SUN_RASTER_HEIGHT_OFFSET)
      );
    }

    final int kind = header.readUnsigned8(NETPBM_KIND_OFFSET);
    final boolean netpbm = header.readUnsigned8(0) == NETPBM_SIGNATURE && isSpace(header.readUnsigned8(NETPBM_SEPARATOR_OFFSET));
    if (netpbm && kind >= PNM_FIRST_KIND && kind <= PNM_LAST_KIND) {
      return readPnmSize(header);
    }

    if (netpbm && kind == PAM_KIND) {
      return readPamSize(header);
    }

    if (netpbm && (kind == PFM_GRAY_KIND || kind == PFM_COLOR_KIND)) {
      return readPfmSize(header);
    }

    if (header.startsWith(0, TIFF_LITTLE_ENDIAN_SIGNATURE) || header.startsWith(0, TIFF_BIG_ENDIAN_SIGNATURE)) {
      return readTiffSize(header);
    }

    if (header.startsWith(0, PNG_SIGNATURE) && header.startsWith(PNG_HEADER_CHUNK_OFFSET, PNG_HEADER_CHUNK)) {
      return createPositiveSize(
        header.readBigEndian(PNG_WIDTH_OFFSET, THIRTY_TWO_BIT_BYTES),
        header.readBigEndian(PNG_HEIGHT_OFFSET, THIRTY_TWO_BIT_BYTES)
      );
    }

    return null;
  }

  private static @Nullable Size readBmpSize(final Header header) {
    final long informationSize = header.readSignedLittleEndian32(BMP_INFORMATION_SIZE_OFFSET);
    if (informationSize >= BMP_MINIMUM_INFORMATION_SIZE) {
      return createPositiveSize(
        header.readSignedLittleEndian32(BMP_WIDTH_OFFSET),
        Math.abs(header.readSignedLittleEndian32(BMP_HEIGHT_OFFSET))
      );
    }

    if (informationSize == OS2_INFORMATION_SIZE) {
      return createPositiveSize(
        header.readLittleEndian(OS2_WIDTH_OFFSET, SIXTEEN_BIT_BYTES),
        header.readLittleEndian(OS2_HEIGHT_OFFSET, SIXTEEN_BIT_BYTES)
      );
    }

    return null;
  }

  private static @Nullable Size readGifSize(final Header header) {
    if (!header.startsWith(0, GIF87_SIGNATURE) && !header.startsWith(0, GIF89_SIGNATURE)) {
      return null;
    }

    return createPositiveSize(
      header.readLittleEndian(GIF_WIDTH_OFFSET, SIXTEEN_BIT_BYTES),
      header.readLittleEndian(GIF_HEIGHT_OFFSET, SIXTEEN_BIT_BYTES)
    );
  }

  // A Radiance HDR: header lines up to an empty one, then the resolution line -Y <height> +X <width>. OpenCV reads the
  // lines in pieces of at most 127 characters, so a piece is what decides here too.
  private static @Nullable Size readHdrSize(final Header header) {
    final long[] position = { 0 };
    // the first line is the magic, which OpenCV reads and does not look at
    header.readPiece(position, MAXIMUM_HDR_PIECE_LENGTH);
    String piece;
    do {
      piece = header.readPiece(position, MAXIMUM_HDR_PIECE_LENGTH);
      if (piece == null) {
        return null;
      }
    } while (piece.charAt(0) != LINE_FEED);
    final String resolution = header.readPiece(position, MAXIMUM_HDR_PIECE_LENGTH);
    if (resolution == null) {
      return null;
    }

    final ResolutionScanner scanner = new ResolutionScanner(resolution);
    final boolean matched =
      scanner.readLiteral(HDR_HEIGHT_AXIS) && scanner.readNumber() && scanner.readLiteral(HDR_WIDTH_AXIS) && scanner.readNumber();
    return matched ? createPositiveSize(scanner.second, scanner.first) : null;
  }

  private static @Nullable Size readJpegSize(final Header header) {
    long position = JPEG_FIRST_MARKER_OFFSET;
    while (true) {
      int code = header.readUnsigned8(position);
      // libjpeg skips whatever is not a marker, then the fill bytes of the marker
      while (code >= 0 && code != JPEG_MARKER_PREFIX) {
        position++;
        code = header.readUnsigned8(position);
      }
      while (code == JPEG_MARKER_PREFIX) {
        position++;
        code = header.readUnsigned8(position);
      }
      position++;
      final boolean frameRange = code >= JPEG_FIRST_FRAME && code <= JPEG_LAST_FRAME;
      final boolean frame = frameRange && code != JPEG_HUFFMAN_TABLE && code != JPEG_EXTENSION && code != JPEG_ARITHMETIC_CODING;
      if (frame) {
        final long width = header.readBigEndian(position + JPEG_FRAME_WIDTH_OFFSET, SIXTEEN_BIT_BYTES);
        final long height = header.readBigEndian(position + JPEG_FRAME_HEIGHT_OFFSET, SIXTEEN_BIT_BYTES);
        return createPositiveSize(width, height);
      }
      // FF 00 is a stuffed zero, no marker: libjpeg's next_marker drops it and looks on, as after a marker that stands
      // alone; read as a marker with a length, it would lead the search away from the frame libjpeg reads
      final boolean restart = code >= JPEG_FIRST_RESTART && code <= JPEG_LAST_RESTART;
      final boolean standalone = code == JPEG_STUFFED_ZERO || code == JPEG_TEMPORARY || restart;
      if (standalone) {
        continue;
      }
      // a second start of the image, the end of the image, the start of a scan before a frame or the end of the bytes
      final boolean noFrame = code < 0 || code == JPEG_START_OF_IMAGE || code == JPEG_END_OF_IMAGE || code == JPEG_START_OF_SCAN;
      final long length = header.readBigEndian(position, SIXTEEN_BIT_BYTES);
      // the length counts its own two bytes
      if (noFrame || length < SIXTEEN_BIT_BYTES) {
        return null;
      }
      position += length;
    }
  }

  // A WebP, which OpenCV recognizes by libwebp reading the features of its first 32 bytes: the canvas of an extended
  // file, or the frame of a lossy or lossless one. A lossless bitstream without its RIFF container is read too; a lossy
  // one never passes OpenCV's signature check, as its first partition is longer than those 32 bytes.
  private static @Nullable Size readWebpSize(final Header header) {
    if (header.size() < MINIMUM_WEBP_HEADER_LENGTH) {
      return null;
    }

    if (!header.startsWith(0, RIFF_SIGNATURE)) {
      return readLosslessWebpSize(header, 0);
    }

    if (!header.startsWith(WEBP_FORMAT_OFFSET, WEBP_FORMAT)) {
      return null;
    }

    if (header.startsWith(WEBP_CHUNK_OFFSET, VP8X_CHUNK)) {
      return readExtendedWebpSize(header);
    }

    if (header.startsWith(WEBP_CHUNK_OFFSET, VP8L_CHUNK)) {
      return readLosslessWebpSize(header, VP8L_BITSTREAM_OFFSET);
    }

    final boolean keyFrame = (header.readUnsigned8(VP8_FRAME_TAG_OFFSET) & VP8_KEY_FRAME_BIT) == 0;
    final boolean startCode = header.startsWith(VP8_START_CODE_OFFSET, VP8_START_CODE);
    final boolean lossy = header.startsWith(WEBP_CHUNK_OFFSET, VP8_CHUNK) && keyFrame && startCode;
    if (!lossy) {
      return null;
    }

    final long width = header.readLittleEndian(VP8_WIDTH_OFFSET, SIXTEEN_BIT_BYTES) & VP8_DIMENSION_MASK;
    final long height = header.readLittleEndian(VP8_HEIGHT_OFFSET, SIXTEEN_BIT_BYTES) & VP8_DIMENSION_MASK;
    return createPositiveSize(width, height);
  }

  private static @Nullable Size readExtendedWebpSize(final Header header) {
    final boolean chunk = header.readLittleEndian(VP8X_CHUNK_SIZE_OFFSET, THIRTY_TWO_BIT_BYTES) == VP8X_CHUNK_SIZE;
    if (!chunk) {
      return null;
    }

    // the canvas size is stored minus one
    final long width = header.readLittleEndian(VP8X_WIDTH_OFFSET, TWENTY_FOUR_BIT_BYTES) + 1;
    final long height = header.readLittleEndian(VP8X_HEIGHT_OFFSET, TWENTY_FOUR_BIT_BYTES) + 1;
    return createPositiveSize(width, height);
  }

  private static @Nullable Size readLosslessWebpSize(final Header header, final long start) {
    final long bits = header.readLittleEndian(start + 1, THIRTY_TWO_BIT_BYTES);
    final boolean signature = header.readUnsigned8(start) == VP8L_SIGNATURE && bits >>> VP8L_VERSION_SHIFT == 0;
    if (!signature) {
      return null;
    }

    // the size is stored minus one
    final long width = (bits & VP8_DIMENSION_MASK) + 1;
    final long height = ((bits >>> VP8L_HEIGHT_SHIFT) & VP8_DIMENSION_MASK) + 1;
    return createPositiveSize(width, height);
  }

  private static @Nullable Size readPnmSize(final Header header) {
    final long[] position = { PNM_HEADER_OFFSET };
    final long width = readPnmNumber(header, position);
    final long height = readPnmNumber(header, position);
    return createPositiveSize(width, height);
  }

  private static long readPnmNumber(final Header header, final long[] position) {
    final int first = skipPnmSeparators(header, position);
    if (!isDigit(first)) {
      return MISSING;
    }

    return readPnmDecimal(header, position, first);
  }

  private static int skipPnmSeparators(final Header header, final long[] position) {
    int code = header.readUnsigned8(position[0]++);
    while (code == COMMENT_START_CODE || isSpace(code)) {
      code = skipPnmSeparator(header, position, code);
    }

    return code;
  }

  private static int skipPnmSeparator(final Header header, final long[] position, final int first) {
    if (first == COMMENT_START_CODE) {
      return skipPnmComment(header, position);
    }

    return skipPnmSpaces(header, position, first);
  }

  private static int skipPnmComment(final Header header, final long[] position) {
    int code;
    do {
      code = header.readUnsigned8(position[0]++);
    } while (code >= 0 && code != LINE_FEED && code != CARRIAGE_RETURN);

    return header.readUnsigned8(position[0]++);
  }

  private static int skipPnmSpaces(final Header header, final long[] position, final int first) {
    int code = first;
    while (isSpace(code)) {
      code = header.readUnsigned8(position[0]++);
    }

    return code;
  }

  private static long readPnmDecimal(final Header header, final long[] position, final int first) {
    int code = first;
    long value = 0;
    while (isDigit(code) && value <= Integer.MAX_VALUE) {
      value = value * DECIMAL_BASE + (code - FIRST_DIGIT);
      code = header.readUnsigned8(position[0]++);
    }

    return value <= Integer.MAX_VALUE ? value : MISSING;
  }

  // A PAM: lines of a name and a value after P7, up to ENDHDR; WIDTH and HEIGHT give the size. Comments start with #.
  private static @Nullable Size readPamSize(final Header header) {
    long width = MISSING;
    long height = MISSING;
    final long[] position = { PAM_HEADER_OFFSET };
    while (true) {
      final String line = header.readLine(position, MAXIMUM_PAM_LINE_LENGTH);
      if (line == null) {
        return null;
      }
      final String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_START)) {
        continue;
      }
      final String[] parts = PAM_SEPARATOR.split(trimmed, PAM_LINE_PARTS);
      final String name = parts[0];
      final String value = parts.length > 1 ? parts[1] : "";
      if (PAM_END.equals(name)) {
        return createPositiveSize(width, height);
      }

      if (!PAM_FIELDS.contains(name)) {
        return null;
      }

      if (PAM_WIDTH.equals(name)) {
        width = width < 0 ? parseDecimal(value) : REFUSED;
      }

      if (PAM_HEIGHT.equals(name)) {
        height = height < 0 ? parseDecimal(value) : REFUSED;
      }
    }
  }

  private static @Nullable Size readPfmSize(final Header header) {
    if (header.readUnsigned8(NETPBM_SEPARATOR_OFFSET) != LINE_FEED) {
      return null;
    }

    final long[] position = { PFM_HEADER_OFFSET };
    final String width = header.readWord(position, MAXIMUM_PFM_NUMBER_LENGTH);
    final String height = header.readWord(position, MAXIMUM_PFM_NUMBER_LENGTH);
    return createPositiveSize(parseLeadingInteger(width), parseLeadingInteger(height));
  }

  private static @Nullable Size readTiffSize(final Header header) {
    final ByteOrder order = header.startsWith(0, TIFF_LITTLE_ENDIAN_SIGNATURE) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    final long magic = header.readNumber(TIFF_MAGIC_OFFSET, SIXTEEN_BIT_BYTES, order);
    final TiffLayout layout = magic == BIG_TIFF_MAGIC ? TiffLayout.BIG : TiffLayout.CLASSIC;
    if (magic != TIFF_MAGIC && layout == TiffLayout.CLASSIC) {
      return null;
    }

    final long directory =
      layout == TiffLayout.BIG
        ? header.readNumber(BIG_TIFF_DIRECTORY_OFFSET, SIXTY_FOUR_BIT_BYTES, order)
        : header.readNumber(TIFF_DIRECTORY_OFFSET, THIRTY_TWO_BIT_BYTES, order);
    final int countSize = layout == TiffLayout.BIG ? SIXTY_FOUR_BIT_BYTES : SIXTEEN_BIT_BYTES;
    final int entrySize = layout == TiffLayout.BIG ? BIG_TIFF_ENTRY_SIZE : TIFF_ENTRY_SIZE;
    final long count = header.readNumber(directory, countSize, order);
    if (count < 0) {
      return null;
    }

    long width = MISSING;
    long length = MISSING;
    final long entries = Math.min(count, header.size() / entrySize);
    for (long index = 0; index < entries; index++) {
      final long entry = directory + countSize + index * entrySize;
      final long tag = header.readNumber(entry, SIXTEEN_BIT_BYTES, order);
      // a tag given twice is refused, as which of the two libtiff keeps is not something to rely on
      if (tag == TIFF_IMAGE_WIDTH_TAG) {
        width = width == MISSING ? readTiffValue(header, entry, layout, order) : REFUSED;
      } else if (tag == TIFF_IMAGE_LENGTH_TAG) {
        length = length == MISSING ? readTiffValue(header, entry, layout, order) : REFUSED;
      }
    }

    return createPositiveSize(width, length);
  }

  private static long readTiffValue(final Header header, final long entry, final TiffLayout layout, final ByteOrder order) {
    final long type = header.readNumber(entry + TIFF_TYPE_OFFSET, SIXTEEN_BIT_BYTES, order);
    final int countBytes = layout == TiffLayout.BIG ? SIXTY_FOUR_BIT_BYTES : THIRTY_TWO_BIT_BYTES;
    final long count = header.readNumber(entry + TIFF_COUNT_OFFSET, countBytes, order);
    final long field = entry + (layout == TiffLayout.BIG ? BIG_TIFF_VALUE_OFFSET : TIFF_VALUE_OFFSET);
    final int bytes;
    if (type == TIFF_SHORT_TYPE) {
      bytes = SIXTEEN_BIT_BYTES;
    } else if (type == TIFF_LONG_TYPE) {
      bytes = THIRTY_TWO_BIT_BYTES;
    } else if (type == TIFF_LONG8_TYPE && layout == TiffLayout.BIG) {
      bytes = SIXTY_FOUR_BIT_BYTES;
    } else {
      return REFUSED;
    }

    return count == 1 ? header.readNumber(field, bytes, order) : REFUSED;
  }

  private static long parseDecimal(final String value) {
    final boolean digits =
      !value.isEmpty() && value.length() <= MAXIMUM_DECIMAL_DIGITS && value.chars().allMatch(DeclaredImageSize::isDigit);
    return digits ? Long.parseLong(value) : REFUSED;
  }

  private static long parseLeadingInteger(final @Nullable String word) {
    if (word == null) {
      return MISSING;
    }

    final int start = word.startsWith(PLUS_SIGN) || word.startsWith(MINUS_SIGN) ? 1 : 0;
    int end = start;
    while (end < word.length() && end - start <= MAXIMUM_DECIMAL_DIGITS && isDigit(word.charAt(end))) {
      end++;
    }

    final String digits = word.substring(start, end);
    final long magnitude = digits.isEmpty() || digits.length() > MAXIMUM_DECIMAL_DIGITS ? MISSING : Long.parseLong(digits);
    return word.startsWith(MINUS_SIGN) ? -magnitude : magnitude;
  }

  private static @Nullable Size createPositiveSize(final long width, final long height) {
    final boolean positive = width > 0 && height > 0;
    return positive ? new Size(width, height) : null;
  }

  private static boolean isDigit(final int code) {
    return code >= FIRST_DIGIT && code <= LAST_DIGIT;
  }

  // C's isspace in the C locale
  static boolean isSpace(final int code) {
    return code == SPACE || (code >= HORIZONTAL_TAB && code <= CARRIAGE_RETURN);
  }

  record Size(long width, long height) {}

  private enum TiffLayout {
    CLASSIC,
    BIG,
  }

  private static final class ResolutionScanner {

    private final String text;
    private int position;
    private long first = MISSING;
    private long second = MISSING;

    ResolutionScanner(final String text) {
      final int end = text.indexOf(STRING_TERMINATOR);
      this.text = end < 0 ? text : text.substring(0, end);
    }

    boolean readLiteral(final String expected) {
      this.skipSpace();
      final boolean matches = this.text.startsWith(expected, this.position);
      this.position += expected.length();

      return matches;
    }

    boolean readNumber() {
      this.skipSpace();
      final int start = this.position;
      final boolean negative = this.text.startsWith(MINUS_SIGN, start);
      if (negative || this.text.startsWith(PLUS_SIGN, start)) {
        this.position++;
      }

      long value = 0;
      int digits = 0;
      while (this.position < this.text.length() && isDigit(this.text.charAt(this.position)) && value <= Integer.MAX_VALUE) {
        value = value * DECIMAL_BASE + (this.text.charAt(this.position) - FIRST_DIGIT);
        this.position++;
        digits++;
      }

      if (digits == 0 || value > Integer.MAX_VALUE) {
        return false;
      }

      final long signed = negative ? -value : value;
      if (this.first == MISSING) {
        this.first = signed;
      } else {
        this.second = signed;
      }

      return true;
    }

    private void skipSpace() {
      while (this.position < this.text.length() && isSpace(this.text.charAt(this.position))) {
        this.position++;
      }
    }
  }

  private static final class Header {

    private final MemorySegment segment;

    Header(final MemorySegment segment) {
      this.segment = segment;
    }

    long size() {
      return this.segment.byteSize();
    }

    int readUnsigned8(final long position) {
      final boolean inside = position >= 0 && position < this.segment.byteSize();
      return inside ? Byte.toUnsignedInt(this.segment.get(ValueLayout.JAVA_BYTE, position)) : END_OF_DATA;
    }

    boolean startsWith(final long position, final String text) {
      for (int index = 0; index < text.length(); index++) {
        if (this.readUnsigned8(position + index) != text.charAt(index)) {
          return false;
        }
      }

      return true;
    }

    long readLittleEndian(final long position, final int bytes) {
      return this.readNumber(position, bytes, ByteOrder.LITTLE_ENDIAN);
    }

    long readBigEndian(final long position, final int bytes) {
      return this.readNumber(position, bytes, ByteOrder.BIG_ENDIAN);
    }

    long readNumber(final long position, final int bytes, final ByteOrder order) {
      final boolean littleEndian = order == ByteOrder.LITTLE_ENDIAN;
      long value = 0;
      for (int index = 0; index < bytes; index++) {
        final int code = this.readUnsigned8(littleEndian ? position + bytes - 1 - index : position + index);
        if (code < 0) {
          return MISSING;
        }
        value = (value << BITS_PER_BYTE) | code;
      }

      return value;
    }

    long readSignedLittleEndian32(final long position) {
      final long value = this.readLittleEndian(position, THIRTY_TWO_BIT_BYTES);
      return value < 0 ? Long.MIN_VALUE : (int) value;
    }

    long readSignedBigEndian32(final long position) {
      final long value = this.readBigEndian(position, THIRTY_TWO_BIT_BYTES);
      return value < 0 ? Long.MIN_VALUE : (int) value;
    }

    @Nullable String readPiece(final long[] position, final int limit) {
      final StringBuilder piece = new StringBuilder();
      int code = 0;
      while (piece.length() < limit && code != LINE_FEED) {
        code = this.readUnsigned8(position[0]);
        if (code < 0) {
          break;
        }
        piece.append((char) code);
        position[0]++;
      }

      return piece.isEmpty() ? null : piece.toString();
    }

    // A line ended by a line feed or a carriage return, without the end. Only the first limit characters are kept: a
    // longer line can only be a comment, as OpenCV refuses a longer name or value.
    @Nullable String readLine(final long[] position, final int limit) {
      final StringBuilder line = new StringBuilder();
      int code = this.readUnsigned8(position[0]++);
      if (code < 0) {
        return null;
      }

      boolean longer = false;
      while (code >= 0 && code != LINE_FEED && code != CARRIAGE_RETURN) {
        if (line.length() < limit) {
          line.append((char) code);
        } else {
          longer = true;
        }
        code = this.readUnsigned8(position[0]++);
      }

      final String text = line.toString();
      final boolean comment = text.strip().startsWith(COMMENT_START);
      return longer && !comment ? null : text;
    }

    @Nullable String readWord(final long[] position, final int limit) {
      final StringBuilder word = new StringBuilder();
      while (word.length() < limit) {
        final int code = this.readUnsigned8(position[0]++);
        if (code < 0) {
          return null;
        }
        if (isSpace(code)) {
          break;
        }
        word.append((char) code);
      }

      return word.toString();
    }
  }
}
