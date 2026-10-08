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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Refuses an encoded image whose header declares more pixels than the limit, before OpenCV decodes it.
 *
 * <p>OpenCV allocates the whole picture as soon as it has read the header, so a small file that declares a huge size
 * (a decompression bomb) would take gigabytes of native memory before anything could look at the result. The header
 * is therefore read here first, the way OpenCV's own decoder for the format reads it: BMP, GIF, Radiance HDR, JPEG,
 * WebP, Sun raster, the Netpbm formats (PBM, PGM, PPM, PAM, PFM), TIFF and PNG, which are the formats the bundled
 * OpenCV decodes. Bytes in any other format are refused as unsupported, as OpenCV refuses them too: JPEG 2000 and
 * OpenEXR, which OpenCV disables unless an environment variable turns them on, are refused as well.
 *
 * <p>The limit is the system property {@value #MAX_PIXELS_PROPERTY}, {@value #DEFAULT_MAX_PIXELS} pixels (8192 by 8192)
 * if it is not set. A value of 0 or less turns the check off and leaves every image to OpenCV, as before the check.
 */
final class DeclaredImageSize {

  /** The system property that sets the most pixels an encoded image may declare; 0 or less turns the check off. */
  static final String MAX_PIXELS_PROPERTY = "mcav.image.maxPixels";

  /** The limit when the property is not set: 8192 by 8192 pixels, 192 MiB of decoded BGR. */
  static final long DEFAULT_MAX_PIXELS = 1L << 26;

  private static final String UNSUPPORTED_BYTES = "Bytes are not a supported image format";
  private static final String UNSUPPORTED_FILE = "File is not a supported image: %s";
  private static final String TOO_LARGE =
    "Image declares %sx%s pixels, more than the %s pixels mcav decodes (the system property " + MAX_PIXELS_PROPERTY + " raises the limit)";

  // OpenCV reads its header lines in pieces of at most 127 characters, the way fgets does with a 128-byte buffer
  private static final int HDR_PIECE = 127;
  // OpenCV's PFM reader reads a number of at most 2048 characters
  private static final int PFM_NUMBER = 2048;
  // OpenCV's PAM reader takes a name of at most 8 characters and a value of at most 255
  private static final int PAM_LINE = 512;
  private static final int WEBP_SIGNATURE = 32;

  private DeclaredImageSize() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks the header of an encoded image in memory.
   *
   * @param encoded the encoded image
   * @throws IllegalArgumentException if the bytes are not a supported image format or declare too many pixels
   */
  static void checkBytes(final byte[] encoded) {
    checkBytes(encoded, limit());
  }

  static void checkBytes(final byte[] encoded, final long limit) {
    if (limit <= 0) {
      return;
    }
    final MemorySegment segment = MemorySegment.ofArray(encoded);
    final Size size = read(segment);
    if (size == null) {
      throw new IllegalArgumentException(UNSUPPORTED_BYTES);
    }
    checkLimit(size, limit);
  }

  /**
   * Checks the header of an image file, mapping the file instead of reading it whole.
   *
   * @param path the image file
   * @throws IllegalArgumentException if the file cannot be read, is not a supported image format or declares too many
   *                                  pixels
   */
  static void checkFile(final Path path) {
    checkFile(path, limit());
  }

  static void checkFile(final Path path, final long limit) {
    if (limit <= 0) {
      return;
    }
    final Size size;
    try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ); final Arena arena = Arena.ofConfined()) {
      final long length = channel.size();
      final MemorySegment segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, length, arena);
      size = read(segment);
    } catch (final IOException failure) {
      throw new IllegalArgumentException(String.format(UNSUPPORTED_FILE, path), failure);
    }
    if (size == null) {
      throw new IllegalArgumentException(String.format(UNSUPPORTED_FILE, path));
    }
    checkLimit(size, limit);
  }

  static long limit() {
    return Long.getLong(MAX_PIXELS_PROPERTY, DEFAULT_MAX_PIXELS);
  }

  private static void checkLimit(final Size size, final long limit) {
    final long width = size.width();
    final long height = size.height();
    final boolean tooLarge = width > limit / height;
    if (tooLarge) {
      throw new IllegalArgumentException(String.format(TOO_LARGE, width, height, limit));
    }
  }

  /**
   * Reads the declared size, trying the formats in the order in which OpenCV tries its decoders.
   *
   * @return the size, or null if the bytes are in no supported format or their header is broken
   */
  static @Nullable Size read(final MemorySegment segment) {
    final Header header = new Header(segment);
    if (header.startsWith(0, "BM")) {
      return bmp(header);
    }
    if (header.startsWith(0, "GIF")) {
      return gif(header);
    }
    if (header.startsWith(0, "#?RGBE") || header.startsWith(0, "#?RADIANCE")) {
      return hdr(header);
    }
    if (header.startsWith(0, "\u00FF\u00D8\u00FF")) {
      return jpeg(header);
    }
    final Size webp = webp(header);
    if (webp != null) {
      return webp;
    }
    if (header.startsWith(0, "Y\u00A6j\u0095")) {
      return of(header.signedBe32(4), header.signedBe32(8));
    }
    final int kind = header.u8(1);
    final boolean netpbm = header.u8(0) == 'P' && isSpace(header.u8(2));
    if (netpbm && kind >= '1' && kind <= '6') {
      return pnm(header);
    }
    if (netpbm && kind == '7') {
      return pam(header);
    }
    if (netpbm && (kind == 'f' || kind == 'F')) {
      return pfm(header);
    }
    if (header.startsWith(0, "II") || header.startsWith(0, "MM")) {
      return tiff(header);
    }
    if (header.startsWith(0, "\u0089PNG\r\n\u001A\n") && header.startsWith(12, "IHDR")) {
      return of(header.be(16, 4), header.be(20, 4));
    }
    return null;
  }

  /**
   * A BMP: the width and height follow the size of the info header, as 32-bit values (the height negative for a
   * top-down picture) or, in the 12-byte OS/2 header, as 16-bit values.
   */
  private static @Nullable Size bmp(final Header header) {
    final long infoSize = header.signedLe32(14);
    if (infoSize >= 36) {
      return of(header.signedLe32(18), Math.abs(header.signedLe32(22)));
    }
    if (infoSize == 12) {
      return of(header.le(18, 2), header.le(20, 2));
    }
    return null;
  }

  /**
   * A GIF: OpenCV draws every frame onto a canvas of the logical screen's size.
   */
  private static @Nullable Size gif(final Header header) {
    if (!header.startsWith(0, "GIF87a") && !header.startsWith(0, "GIF89a")) {
      return null;
    }
    return of(header.le(6, 2), header.le(8, 2));
  }

  /**
   * A Radiance HDR: header lines up to an empty one, then the resolution line {@code -Y <height> +X <width>}. OpenCV
   * reads the lines in pieces of at most 127 characters, so a piece is what decides here too.
   */
  private static @Nullable Size hdr(final Header header) {
    final long[] position = { 0 };
    // the first line is the magic, which OpenCV reads and does not look at
    header.piece(position, HDR_PIECE);
    String piece;
    do {
      piece = header.piece(position, HDR_PIECE);
      if (piece == null) {
        return null;
      }
    } while (piece.charAt(0) != '\n');
    final String resolution = header.piece(position, HDR_PIECE);
    if (resolution == null) {
      return null;
    }
    final Scanner scanner = new Scanner(resolution);
    final boolean matched = scanner.literal("-Y") && scanner.number() && scanner.literal("+X") && scanner.number();
    return matched ? of(scanner.second, scanner.first) : null;
  }

  /**
   * A JPEG: the markers after the start of the image are skipped by their lengths, the way libjpeg reads them, up to the
   * first start of a frame, which holds the height and the width.
   */
  private static @Nullable Size jpeg(final Header header) {
    long position = 2;
    while (true) {
      int code = header.u8(position);
      // libjpeg skips whatever is not a marker, then the fill bytes of the marker
      while (code >= 0 && code != 0xFF) {
        position++;
        code = header.u8(position);
      }
      while (code == 0xFF) {
        position++;
        code = header.u8(position);
      }
      position++;
      final boolean frame = code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC;
      if (frame) {
        return of(header.be(position + 5, 2), header.be(position + 3, 2));
      }
      // FF 00 is a stuffed zero, no marker: libjpeg's next_marker drops it and looks on, as after a marker that stands
      // alone; read as a marker with a length, it would lead the search away from the frame libjpeg reads
      final boolean standalone = code == 0x00 || code == 0x01 || (code >= 0xD0 && code <= 0xD7);
      if (standalone) {
        continue;
      }
      final boolean noFrame = code < 0 || code == 0xD8 || code == 0xD9 || code == 0xDA;
      final long length = header.be(position, 2);
      if (noFrame || length < 2) {
        return null;
      }
      position += length;
    }
  }

  /**
   * A WebP, which OpenCV recognizes by libwebp reading the features of its first 32 bytes: the canvas of an extended
   * file, or the frame of a lossy or lossless one. A lossless bitstream without its RIFF container is read too; a lossy
   * one never passes OpenCV's signature check, as its first partition is longer than those 32 bytes.
   */
  private static @Nullable Size webp(final Header header) {
    if (header.size() < WEBP_SIGNATURE) {
      return null;
    }
    if (!header.startsWith(0, "RIFF")) {
      return lossless(header, 0);
    }
    if (!header.startsWith(8, "WEBP")) {
      return null;
    }
    if (header.startsWith(12, "VP8X")) {
      final boolean chunk = header.le(16, 4) == 10;
      return chunk ? of(header.le(24, 3) + 1, header.le(27, 3) + 1) : null;
    }
    if (header.startsWith(12, "VP8L")) {
      return lossless(header, 20);
    }
    final boolean keyFrame = (header.u8(20) & 1) == 0;
    final boolean lossy = header.startsWith(12, "VP8 ") && keyFrame && header.startsWith(23, "\u009D\u0001*");
    return lossy ? of(header.le(26, 2) & 0x3FFF, header.le(28, 2) & 0x3FFF) : null;
  }

  private static @Nullable Size lossless(final Header header, final long start) {
    final long bits = header.le(start + 1, 4);
    final boolean signature = header.u8(start) == 0x2F && bits >>> 29 == 0;
    return signature ? of((bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1) : null;
  }

  /**
   * A PBM, PGM or PPM: the width and the height are the first two numbers after the magic, separated by whitespace
   * and comments that run from {@code #} to the end of their line.
   */
  private static @Nullable Size pnm(final Header header) {
    final long[] position = { 2 };
    final long width = pnmNumber(header, position);
    final long height = pnmNumber(header, position);
    return of(width, height);
  }

  private static long pnmNumber(final Header header, final long[] position) {
    int code = header.u8(position[0]++);
    while (!isDigit(code)) {
      if (code == '#') {
        do {
          code = header.u8(position[0]++);
        } while (code >= 0 && code != '\n' && code != '\r');
        code = header.u8(position[0]++);
      } else if (isSpace(code)) {
        while (isSpace(code)) {
          code = header.u8(position[0]++);
        }
      } else {
        return -1;
      }
    }
    long value = 0;
    while (isDigit(code) && value <= Integer.MAX_VALUE) {
      value = value * 10 + (code - '0');
      code = header.u8(position[0]++);
    }
    return value <= Integer.MAX_VALUE ? value : -1;
  }

  /**
   * A PAM: lines of a name and a value after {@code P7}, up to {@code ENDHDR}; {@code WIDTH} and {@code HEIGHT} give the
   * size. Comments start with {@code #}.
   */
  private static @Nullable Size pam(final Header header) {
    long width = -1;
    long height = -1;
    final long[] position = { 3 };
    while (true) {
      final String line = header.line(position, PAM_LINE);
      if (line == null) {
        return null;
      }
      final String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
        continue;
      }
      final String[] parts = trimmed.split("\\s+", 2);
      final String name = parts[0];
      final String value = parts.length > 1 ? parts[1] : "";
      switch (name) {
        case "ENDHDR" -> {
          return of(width, height);
        }
        case "WIDTH" -> width = width < 0 ? decimal(value) : -2;
        case "HEIGHT" -> height = height < 0 ? decimal(value) : -2;
        case "DEPTH", "MAXVAL", "TUPLTYPE" -> {
        }
        default -> {
          return null;
        }
      }
    }
  }

  /**
   * A PFM: the width and the height are the first two whitespace-separated words after the magic line, read the way
   * {@code atoi} reads them: an optional sign and the digits that start the word.
   */
  private static @Nullable Size pfm(final Header header) {
    if (header.u8(2) != '\n') {
      return null;
    }
    final long[] position = { 3 };
    final String width = header.word(position, PFM_NUMBER);
    final String height = header.word(position, PFM_NUMBER);
    return of(leadingInteger(width), leadingInteger(height));
  }

  /**
   * A TIFF or BigTIFF: the image width and length tags of the first directory, which is the page OpenCV decodes.
   */
  private static @Nullable Size tiff(final Header header) {
    final boolean little = header.u8(0) == 'I';
    final long magic = header.number(2, 2, little);
    final boolean big = magic == 43;
    if (magic != 42 && !big) {
      return null;
    }
    final long directory = big ? header.number(8, 8, little) : header.number(4, 4, little);
    final int countSize = big ? 8 : 2;
    final int entrySize = big ? 20 : 12;
    final long count = header.number(directory, countSize, little);
    if (count < 0) {
      return null;
    }
    long width = -1;
    long length = -1;
    final long entries = Math.min(count, header.size() / entrySize);
    for (long index = 0; index < entries; index++) {
      final long entry = directory + countSize + index * entrySize;
      final long tag = header.number(entry, 2, little);
      // a tag given twice is refused, as which of the two libtiff keeps is not something to rely on
      if (tag == 256) {
        width = width == -1 ? tiffValue(header, entry, big, little) : -2;
      } else if (tag == 257) {
        length = length == -1 ? tiffValue(header, entry, big, little) : -2;
      }
    }
    return of(width, length);
  }

  /**
   * The single SHORT, LONG or LONG8 value of a directory entry, stored inside the entry; -2 for any other entry, which
   * libtiff would not accept as a width or a length either.
   */
  private static long tiffValue(final Header header, final long entry, final boolean big, final boolean little) {
    final long type = header.number(entry + 2, 2, little);
    final long count = big ? header.number(entry + 4, 8, little) : header.number(entry + 4, 4, little);
    final long field = entry + (big ? 12 : 8);
    final int bytes;
    if (type == 3) {
      bytes = 2;
    } else if (type == 4) {
      bytes = 4;
    } else if (type == 16 && big) {
      bytes = 8;
    } else {
      return -2;
    }
    return count == 1 ? header.number(field, bytes, little) : -2;
  }

  private static long decimal(final String value) {
    final boolean digits = !value.isEmpty() && value.length() <= 10 && value.chars().allMatch(DeclaredImageSize::isDigit);
    return digits ? Long.parseLong(value) : -2;
  }

  private static long leadingInteger(final @Nullable String word) {
    if (word == null) {
      return -1;
    }
    final int start = word.startsWith("+") || word.startsWith("-") ? 1 : 0;
    int end = start;
    while (end < word.length() && end - start <= 10 && isDigit(word.charAt(end))) {
      end++;
    }
    final String digits = word.substring(start, end);
    final long magnitude = digits.isEmpty() || digits.length() > 10 ? -1 : Long.parseLong(digits);
    return word.startsWith("-") ? -magnitude : magnitude;
  }

  private static @Nullable Size of(final long width, final long height) {
    final boolean positive = width > 0 && height > 0;
    return positive ? new Size(width, height) : null;
  }

  private static boolean isDigit(final int code) {
    return code >= '0' && code <= '9';
  }

  // C's isspace in the C locale
  static boolean isSpace(final int code) {
    return code == ' ' || (code >= '\t' && code <= '\r');
  }

  /**
   * The width and the height an image declares, each at least 1.
   *
   * @param width  the width in pixels
   * @param height the height in pixels
   */
  record Size(long width, long height) {}

  /**
   * The parts of {@code sscanf(line, "-Y %d +X %d", ...)} this class needs: a literal, whitespace, and an int.
   */
  private static final class Scanner {

    private final String text;
    private int position;
    private long first = -1;
    private long second = -1;

    Scanner(final String text) {
      final int end = text.indexOf('\0');
      this.text = end < 0 ? text : text.substring(0, end);
    }

    boolean literal(final String expected) {
      this.skipSpace();
      final boolean matches = this.text.startsWith(expected, this.position);
      this.position += expected.length();
      return matches;
    }

    boolean number() {
      this.skipSpace();
      final int start = this.position;
      final boolean negative = this.text.startsWith("-", start);
      if (negative || this.text.startsWith("+", start)) {
        this.position++;
      }
      long value = 0;
      int digits = 0;
      while (this.position < this.text.length() && isDigit(this.text.charAt(this.position)) && value <= Integer.MAX_VALUE) {
        value = value * 10 + (this.text.charAt(this.position) - '0');
        this.position++;
        digits++;
      }
      if (digits == 0 || value > Integer.MAX_VALUE) {
        return false;
      }
      final long signed = negative ? -value : value;
      if (this.first == -1) {
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

  /**
   * Bounds-checked reads of the encoded bytes: a read past the end gives -1, which no size accepts.
   */
  private static final class Header {

    private final MemorySegment segment;

    Header(final MemorySegment segment) {
      this.segment = segment;
    }

    long size() {
      return this.segment.byteSize();
    }

    int u8(final long position) {
      final boolean inside = position >= 0 && position < this.segment.byteSize();
      return inside ? Byte.toUnsignedInt(this.segment.get(ValueLayout.JAVA_BYTE, position)) : -1;
    }

    boolean startsWith(final long position, final String text) {
      for (int index = 0; index < text.length(); index++) {
        if (this.u8(position + index) != text.charAt(index)) {
          return false;
        }
      }
      return true;
    }

    long le(final long position, final int bytes) {
      return this.number(position, bytes, true);
    }

    long be(final long position, final int bytes) {
      return this.number(position, bytes, false);
    }

    /**
     * An unsigned number of 2 to 8 bytes; -1 if it reaches past the end or does not fit a long.
     */
    long number(final long position, final int bytes, final boolean little) {
      long value = 0;
      for (int index = 0; index < bytes; index++) {
        final int code = this.u8(little ? position + bytes - 1 - index : position + index);
        if (code < 0) {
          return -1;
        }
        value = (value << 8) | code;
      }
      return value;
    }

    long signedLe32(final long position) {
      final long value = this.le(position, 4);
      return value < 0 ? Long.MIN_VALUE : (int) value;
    }

    long signedBe32(final long position) {
      final long value = this.be(position, 4);
      return value < 0 ? Long.MIN_VALUE : (int) value;
    }

    /**
     * What {@code fgets} with a buffer of {@code limit + 1} bytes reads: up to {@code limit} bytes, through the first
     * line feed.
     *
     * @return the piece, or null at the end of the bytes
     */
    @Nullable String piece(final long[] position, final int limit) {
      final StringBuilder piece = new StringBuilder();
      int code = 0;
      while (piece.length() < limit && code != '\n') {
        code = this.u8(position[0]);
        if (code < 0) {
          break;
        }
        piece.append((char) code);
        position[0]++;
      }
      return piece.isEmpty() ? null : piece.toString();
    }

    /**
     * A line ended by a line feed or a carriage return, without the end. Only the first {@code limit} characters are
     * kept: a longer line can only be a comment, as OpenCV refuses a longer name or value.
     *
     * @return the line, or null at the end of the bytes or for a longer line that is not a comment
     */
    @Nullable String line(final long[] position, final int limit) {
      final StringBuilder line = new StringBuilder();
      int code = this.u8(position[0]++);
      if (code < 0) {
        return null;
      }
      boolean longer = false;
      while (code >= 0 && code != '\n' && code != '\r') {
        if (line.length() < limit) {
          line.append((char) code);
        } else {
          longer = true;
        }
        code = this.u8(position[0]++);
      }
      final String text = line.toString();
      final boolean comment = text.strip().startsWith("#");
      return longer && !comment ? null : text;
    }

    /**
     * Up to {@code limit} bytes before the next whitespace, as OpenCV's PFM reader reads a number.
     *
     * @return the word, or null at the end of the bytes
     */
    @Nullable String word(final long[] position, final int limit) {
      final StringBuilder word = new StringBuilder();
      while (word.length() < limit) {
        final int code = this.u8(position[0]++);
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
