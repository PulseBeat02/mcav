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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import static me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet.SYMBOL_BITS;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;

/**
 * The map-page transport of MCV2 frames, bit-exact with the reference's {@code mcv2_reference.py}.
 *
 * <p>A frame is split into pages of at most 16,384 symbols, one map's worth. Each page starts with a 32-byte header
 * ({@code <4sBBHIIHHIII}: magic {@code MCP1}, version 1, useful symbol bits, frame type, stream id, frame id, page
 * number, page count, reference id, frame length, CRC32 of the header with a zero CRC field followed by the payload),
 * then carries the frame bytes from {@code number * capacity}. Header and payload bytes are expanded least significant
 * bit first and regrouped into symbols of 6 bits, least significant bit first; the last symbol is zero-filled.
 *
 * <p>The CRC detects corruption; it is not authentication.
 *
 * <p>Conversion methods allocate independent arrays and do not retain caller buffers. Static operations can
 * run concurrently when inputs are not mutated. Assembly state belongs to a separate {@link PageAssembler}.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class TransportPages {

  private static final String SYMBOLS_NULL_MESSAGE = "Symbols must not be null!";
  private static final String FRAME_NULL_MESSAGE = "Frame must not be null!";
  private static final String STREAM_ID_RANGE_MESSAGE = "Stream id must be an unsigned 32-bit value!";
  private static final String PAGES_NULL_MESSAGE = "Pages must not be null!";

  static final int MAP_SIDE = 128;

  /**
   * Symbols of one page: a full 128x128 map.
   *
   * @since 1.0.0
   */
  public static final int PAGE_SYMBOLS = MAP_SIDE * MAP_SIDE;

  /**
   * Logical bytes of the page header.
   *
   * @since 1.0.0
   */
  public static final int HEADER_BYTES = 32;

  /**
   * The page magic, {@code MCP1}.
   *
   * @since 1.0.0
   */
  public static final int MAGIC = 0x3150434D;

  /**
   * Bytes charged per map packet by the reference's wire model.
   *
   * @since 1.0.0
   */
  public static final int PACKET_OVERHEAD = 18;

  private static final int VERSION = 1;

  private static final int MAGIC_OFFSET = 0;

  private static final int VERSION_OFFSET = 4;

  private static final int SYMBOL_BITS_OFFSET = 5;

  private static final int TYPE_OFFSET = 6;

  private static final int STREAM_OFFSET = 8;

  private static final int FRAME_OFFSET = 12;

  private static final int NUMBER_OFFSET = 16;

  private static final int COUNT_OFFSET = 18;

  private static final int REFERENCE_OFFSET = 20;

  private static final int LENGTH_OFFSET = 24;

  private static final int CRC_OFFSET = 28;

  private TransportPages() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  private static long symbolCount(final long bytes) {
    return (bytes * Byte.SIZE + SYMBOL_BITS - 1) / SYMBOL_BITS;
  }

  /**
   * Returns the payload capacity of one page, after its header.
   *
   * @return the capacity in logical bytes: 12,256
   * @since 1.0.0
   */
  public static int capacity() {
    return (PAGE_SYMBOLS * SYMBOL_BITS) / Byte.SIZE - HEADER_BYTES;
  }

  /**
   * Returns the number of pages a frame of some length needs.
   *
   * @param frameBytes the nonnegative frame length in bytes, within {@link Mcv2Decoder#MAXIMUM_FRAME_BYTES};
   *                   this helper does not validate the length
   * @return the page count
   * @since 1.0.0
   */
  public static int pageCount(final int frameBytes) {
    final int capacity = capacity();
    return (frameBytes + capacity - 1) / capacity;
  }

  /**
   * Packs bytes into symbols, least significant bit first, zero-filling the last symbol.
   *
   * @param data the bytes
   * @return one symbol per byte of the result
   * @throws NullPointerException if {@code data} is null
   * @since 1.0.0
   */
  public static byte[] toSymbols(final byte[] data) {
    Preconditions.checkNotNull(data, "Data must not be null!");

    final byte[] symbols = new byte[(int) symbolCount(data.length)];
    long buffer = 0;
    int held = 0;
    int written = 0;
    for (final byte value : data) {
      buffer |= Byte.toUnsignedLong(value) << held;
      held += Byte.SIZE;
      while (held >= SYMBOL_BITS) {
        symbols[written++] = (byte) (buffer & ((1L << SYMBOL_BITS) - 1));
        buffer >>>= SYMBOL_BITS;
        held -= SYMBOL_BITS;
      }
    }

    if (held > 0) {
      symbols[written] = (byte) buffer;
    }

    return symbols;
  }

  /**
   * Unpacks symbols into bytes, rejecting symbols outside the alphabet, a wrong symbol count and nonzero padding.
   *
   * @param symbols   the symbols, exactly {@code ceil(byteCount * 8 / SYMBOL_BITS)} of them
   * @param byteCount the number of bytes the symbols carry
   * @return the bytes
   * @throws Mcv2Exception        if the symbols do not carry exactly that many bytes with zero padding
   * @throws NullPointerException if {@code symbols} is null
   * @since 1.0.0
   */
  public static byte[] fromSymbols(final byte[] symbols, final int byteCount) {
    Preconditions.checkNotNull(symbols, SYMBOLS_NULL_MESSAGE);

    if (byteCount < 0 || symbols.length != symbolCount(byteCount)) {
      final String message = "Invalid symbol extent!";
      throw new Mcv2Exception(message);
    }

    final byte[] data = new byte[byteCount];
    long buffer = 0;
    int held = 0;
    int written = 0;
    for (final byte symbol : symbols) {
      final int value = Byte.toUnsignedInt(symbol);
      if (value >= 1 << SYMBOL_BITS) {
        final String message = "Out-of-alphabet symbol!";
        throw new Mcv2Exception(message);
      }
      buffer |= (long) value << held;
      held += SYMBOL_BITS;
      while (held >= Byte.SIZE) {
        data[written++] = (byte) buffer;
        buffer >>>= Byte.SIZE;
        held -= Byte.SIZE;
      }
    }

    if (buffer != 0) {
      final String message = "Nonzero symbol padding!";
      throw new Mcv2Exception(message);
    }

    return data;
  }

  /**
   * Splits a frame into pages. The frame is validated first, so only a valid frame can be sent.
   *
   * @param frame    the frame bytes
   * @param streamId the unsigned 32-bit stream id
   * @return the pages, each as its symbols
   * @throws Mcv2Exception            if the bytes are not a valid frame
   * @throws IllegalArgumentException if the stream id is outside unsigned 32-bit range
   * @throws NullPointerException     if {@code frame} is null
   * @since 1.0.0
   */
  public static List<byte[]> makePages(final byte[] frame, final long streamId) {
    Preconditions.checkNotNull(frame, FRAME_NULL_MESSAGE);
    Preconditions.checkArgument(streamId >= 0 && streamId <= Mcv2Decoder.MAXIMUM_UNSIGNED_INT, STREAM_ID_RANGE_MESSAGE);

    return makePages(Mcv2Decoder.parse(frame), streamId);
  }

  /**
   * Splits an already validated frame into six-bit pages.
   *
   * @param parsed   immutable validated frame
   * @param streamId unsigned 32-bit stream id
   * @return pages as symbols
   * @throws NullPointerException     if parsed is null
   * @throws IllegalArgumentException if streamId is outside the unsigned 32-bit range
   * @since 1.0.0
   */
  public static List<byte[]> makePages(final Frame parsed, final long streamId) {
    Preconditions.checkNotNull(parsed, FRAME_NULL_MESSAGE);
    Preconditions.checkArgument(streamId >= 0 && streamId <= Mcv2Decoder.MAXIMUM_UNSIGNED_INT, STREAM_ID_RANGE_MESSAGE);

    final byte[] frame = parsed.getData();
    final int capacity = capacity();
    final int count = pageCount(frame.length);
    final List<byte[]> pages = new ArrayList<>(count);
    for (int number = 0; number < count; number++) {
      final int from = number * capacity;
      final int length = Math.min(capacity, frame.length - from);
      final byte[] page = new byte[HEADER_BYTES + length];
      Mcv2Decoder.putU32(page, MAGIC_OFFSET, MAGIC);
      page[VERSION_OFFSET] = VERSION;
      page[SYMBOL_BITS_OFFSET] = (byte) SYMBOL_BITS;
      Mcv2Decoder.putU16(page, TYPE_OFFSET, parsed.isKeyframe() ? 1 : 0);
      Mcv2Decoder.putU32(page, STREAM_OFFSET, streamId);
      Mcv2Decoder.putU32(page, FRAME_OFFSET, parsed.getFrameId());
      Mcv2Decoder.putU16(page, NUMBER_OFFSET, number);
      Mcv2Decoder.putU16(page, COUNT_OFFSET, count);
      Mcv2Decoder.putU32(page, REFERENCE_OFFSET, parsed.getReferenceId());
      Mcv2Decoder.putU32(page, LENGTH_OFFSET, frame.length);
      System.arraycopy(frame, from, page, HEADER_BYTES, length);
      final CRC32 checksum = new CRC32();
      checksum.update(page);
      Mcv2Decoder.putU32(page, CRC_OFFSET, checksum.getValue());
      pages.add(toSymbols(page));
    }

    return pages;
  }

  /**
   * Returns the number of useful symbols of a page, derived from its header. A map carries whole rows, so the symbols
   * after this count are padding and not part of the page.
   *
   * @param symbols at least the header's symbols
   * @return the useful symbol count
   * @throws Mcv2Exception        if the header is truncated, malformed or inconsistent
   * @throws NullPointerException if the symbol array is null
   * @since 1.0.0
   */
  public static int usefulSymbols(final byte[] symbols) {
    final byte[] header = readHeader(symbols);
    final int size = checkHeader(header);
    return (int) symbolCount(HEADER_BYTES + size);
  }

  private static byte[] readHeader(final byte[] symbols) {
    Preconditions.checkNotNull(symbols, SYMBOLS_NULL_MESSAGE);

    if (symbols.length > PAGE_SYMBOLS) {
      final String message = "Oversize map page!";
      throw new Mcv2Exception(message);
    }

    final int headerSymbols = (int) symbolCount(HEADER_BYTES);
    if (symbols.length < headerSymbols) {
      final String message = "Truncated page header!";
      throw new Mcv2Exception(message);
    }

    final byte[] header = new byte[HEADER_BYTES];
    long buffer = 0;
    int held = 0;
    int written = 0;
    for (int symbolIndex = 0; symbolIndex < headerSymbols; symbolIndex++) {
      buffer |= (long) (symbols[symbolIndex] & ((1 << SYMBOL_BITS) - 1)) << held;
      held += SYMBOL_BITS;
      while (held >= Byte.SIZE) {
        header[written++] = (byte) buffer;
        buffer >>>= Byte.SIZE;
        held -= Byte.SIZE;
      }
    }

    return header;
  }

  private static int checkHeader(final byte[] header) {
    final int capacity = capacity();
    if (
      Mcv2Decoder.u32(header, MAGIC_OFFSET) != MAGIC ||
      header[VERSION_OFFSET] != VERSION ||
      Byte.toUnsignedInt(header[SYMBOL_BITS_OFFSET]) != SYMBOL_BITS ||
      Mcv2Decoder.u16(header, TYPE_OFFSET) > 1
    ) {
      final String message = "Unsupported page header!";
      throw new Mcv2Exception(message);
    }

    final long total = Mcv2Decoder.u32(header, LENGTH_OFFSET);
    final int number = Mcv2Decoder.u16(header, NUMBER_OFFSET);
    final int count = Mcv2Decoder.u16(header, COUNT_OFFSET);
    if (
      total < Mcv2Decoder.HEADER_BYTES ||
      total > Mcv2Decoder.MAXIMUM_FRAME_BYTES ||
      count != (total + capacity - 1) / capacity ||
      number >= count
    ) {
      final String message = "Invalid page metadata!";
      throw new Mcv2Exception(message);
    }

    return (int) Math.min(capacity, total - (long) number * capacity);
  }

  /**
   * Validates a page before it is stored, exactly as the reference's {@code read_page}: the symbols must be exactly
   * the page's useful symbols, every symbol inside the alphabet, the padding zero and the CRC correct.
   *
   * @param symbols the page's useful symbols
   * @return the page
   * @throws Mcv2Exception        if the page is malformed or its CRC does not match
   * @throws NullPointerException if the symbol array is null
   * @since 1.0.0
   */
  public static TransportPage readPage(final byte[] symbols) {
    final byte[] header = readHeader(symbols);
    final int size = checkHeader(header);
    final byte[] pageBytes = fromSymbols(symbols, HEADER_BYTES + size);
    final long stored = Mcv2Decoder.u32(pageBytes, CRC_OFFSET);
    Mcv2Decoder.putU32(pageBytes, CRC_OFFSET, 0);
    final CRC32 checksum = new CRC32();
    checksum.update(pageBytes);

    if (checksum.getValue() != stored) {
      final String message = "Page CRC mismatch!";
      throw new Mcv2Exception(message);
    }

    final byte[] payload = new byte[size];
    System.arraycopy(pageBytes, HEADER_BYTES, payload, 0, size);
    return new TransportPage(
      Mcv2Decoder.u32(pageBytes, STREAM_OFFSET),
      Mcv2Decoder.u32(pageBytes, FRAME_OFFSET),
      Mcv2Decoder.u16(pageBytes, NUMBER_OFFSET),
      Mcv2Decoder.u16(pageBytes, COUNT_OFFSET),
      Mcv2Decoder.u32(pageBytes, REFERENCE_OFFSET),
      (int) Mcv2Decoder.u32(pageBytes, LENGTH_OFFSET),
      Mcv2Decoder.u16(pageBytes, TYPE_OFFSET),
      SYMBOL_BITS,
      payload
    );
  }

  /**
   * Returns the bytes the pages take on the wire under the reference's conservative, uncompressed Map Data model:
   * every page is sent as whole 128-symbol rows plus a fixed per-packet envelope.
   *
   * @param pages          the pages, each as its useful symbols
   * @param packetOverhead the nonnegative estimated bytes charged per packet; not validated here
   * @return the modeled wire bytes
   * @throws NullPointerException if {@code pages} is null
   * @see #fullMapWireBytes(List, int)
   * @since 1.0.0
   */
  public static long wireBytes(final List<byte[]> pages, final int packetOverhead) {
    Preconditions.checkNotNull(pages, PAGES_NULL_MESSAGE);

    long total = 0;
    for (final byte[] page : pages) {
      total += (long) Math.ceilDiv(page.length, MAP_SIDE) * MAP_SIDE + packetOverhead;
    }

    return total;
  }

  /**
   * Returns the bytes the pages take on the wire under the model of {@link #wireBytes(List, int)}, with every page
   * sent as a complete map instead of whole rows.
   *
   * @param pages          the pages, each as its useful symbols
   * @param packetOverhead the nonnegative estimated bytes charged per packet; not validated here
   * @return the modeled wire bytes
   * @throws NullPointerException if {@code pages} is null
   * @since 1.0.0
   */
  public static long fullMapWireBytes(final List<byte[]> pages, final int packetOverhead) {
    Preconditions.checkNotNull(pages, PAGES_NULL_MESSAGE);

    final long pageBytes = (long) PAGE_SYMBOLS + packetOverhead;
    return pages.size() * pageBytes;
  }
}
