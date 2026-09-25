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
package me.brandonli.mcav.media.mcv2.transport;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;

/**
 * The map-page transport of MCV2 frames, bit-exact with the reference's {@code transport.py}.
 *
 * <p>A frame is split into pages of at most 16,384 symbols, one map's worth. Each page starts with a 32-byte header
 * ({@code <4sBBHIIHHIII}: magic {@code MCP1}, version 1, useful symbol bits, frame type, stream id, frame id, page
 * number, page count, reference id, frame length, CRC32 of the header with a zero CRC field followed by the payload),
 * then carries the frame bytes from {@code number * capacity}. Header and payload bytes are expanded least significant
 * bit first and regrouped into symbols of 6, 7 or 8 bits, least significant bit first; the last symbol is zero-filled.
 *
 * <p>The CRC detects corruption; it is not authentication.
 */
public final class TransportPages {

  /** Symbols of one page: a full 128x128 map. */
  public static final int PAGE_SYMBOLS = 128 * 128;

  /** Logical bytes of the page header. */
  public static final int HEADER_BYTES = 32;

  /** The page magic, {@code MCP1}. */
  public static final int MAGIC = 0x3150434D;

  /** Bytes charged per map packet by the reference's wire model. */
  public static final int PACKET_OVERHEAD = 18;

  private TransportPages() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static void checkSymbolBits(final int symbolBits) {
    Preconditions.checkArgument(symbolBits >= 6 && symbolBits <= 8, "Unsupported symbol width: %s", symbolBits);
  }

  /**
   * The payload capacity of one page, after its header.
   *
   * @param symbolBits the useful bits per symbol, 6, 7 or 8
   * @return the capacity in logical bytes: 12,256, 14,304 or 16,352
   */
  public static int capacity(final int symbolBits) {
    checkSymbolBits(symbolBits);
    return (PAGE_SYMBOLS * symbolBits) / 8 - HEADER_BYTES;
  }

  /**
   * The number of pages a frame of some length needs.
   *
   * @param frameBytes the frame length
   * @param symbolBits the useful bits per symbol
   * @return the page count
   */
  public static int pageCount(final int frameBytes, final int symbolBits) {
    final int capacity = capacity(symbolBits);
    return (frameBytes + capacity - 1) / capacity;
  }

  /**
   * Packs bytes into symbols, least significant bit first, zero-filling the last symbol.
   *
   * @param data       the bytes
   * @param symbolBits the bits per symbol, 6, 7 or 8
   * @return one symbol per byte of the result
   */
  public static byte[] toSymbols(final byte[] data, final int symbolBits) {
    Preconditions.checkNotNull(data, "Data must not be null");
    checkSymbolBits(symbolBits);
    final long bits = (long) data.length * 8;
    final byte[] symbols = new byte[(int) ((bits + symbolBits - 1) / symbolBits)];
    long buffer = 0;
    int held = 0;
    int out = 0;
    for (final byte value : data) {
      buffer |= (long) (value & 0xFF) << held;
      held += 8;
      while (held >= symbolBits) {
        symbols[out++] = (byte) (buffer & ((1L << symbolBits) - 1));
        buffer >>>= symbolBits;
        held -= symbolBits;
      }
    }
    if (held > 0) {
      symbols[out] = (byte) buffer;
    }
    return symbols;
  }

  /**
   * Unpacks symbols into bytes, rejecting symbols outside the alphabet, a wrong symbol count and nonzero padding.
   *
   * @param symbols    the symbols, exactly {@code ceil(byteCount * 8 / symbolBits)} of them
   * @param symbolBits the bits per symbol
   * @param byteCount  the number of bytes the symbols carry
   * @return the bytes
   * @throws Mcv2Exception if the symbols do not carry exactly that many bytes with zero padding
   */
  public static byte[] fromSymbols(final byte[] symbols, final int symbolBits, final int byteCount) throws Mcv2Exception {
    Preconditions.checkNotNull(symbols, "Symbols must not be null");
    if (symbolBits < 6 || symbolBits > 8 || byteCount < 0 || symbols.length != ((long) byteCount * 8 + symbolBits - 1) / symbolBits) {
      throw new Mcv2Exception("Invalid symbol extent");
    }
    final byte[] data = new byte[byteCount];
    long buffer = 0;
    int held = 0;
    int out = 0;
    // the extent check bounds the symbols' bits below byteCount * 8 + symbolBits, so at most byteCount bytes fill
    for (final byte symbol : symbols) {
      final int value = symbol & 0xFF;
      if (value >= 1 << symbolBits) {
        throw new Mcv2Exception("Out-of-alphabet symbol");
      }
      buffer |= (long) value << held;
      held += symbolBits;
      while (held >= 8) {
        data[out++] = (byte) buffer;
        buffer >>>= 8;
        held -= 8;
      }
    }
    if (buffer != 0) {
      throw new Mcv2Exception("Nonzero symbol padding");
    }
    return data;
  }

  /**
   * Splits a frame into pages. The frame is validated first, so only a valid frame can be sent.
   *
   * @param frame      the frame bytes
   * @param streamId   the unsigned 32-bit stream id
   * @param symbolBits the useful bits per symbol, 6, 7 or 8
   * @return the pages, each as its symbols
   * @throws Mcv2Exception if the bytes are not a valid frame
   */
  public static List<byte[]> makePages(final byte[] frame, final long streamId, final int symbolBits) throws Mcv2Exception {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    Preconditions.checkArgument(streamId >= 0 && streamId <= 0xFFFFFFFFL, "Stream id must be an unsigned 32-bit value");
    checkSymbolBits(symbolBits);
    final Mcv2Frame parsed = FrameParser.parse(frame);
    final int capacity = capacity(symbolBits);
    final int count = pageCount(frame.length, symbolBits);
    final List<byte[]> pages = new ArrayList<>(count);
    for (int number = 0; number < count; number++) {
      final int from = number * capacity;
      final int length = Math.min(capacity, frame.length - from);
      final byte[] page = new byte[HEADER_BYTES + length];
      Mcv2Format.putU32(page, 0, MAGIC);
      page[4] = 1;
      page[5] = (byte) symbolBits;
      Mcv2Format.putU16(page, 6, parsed.getFlags() & Mcv2Format.KEYFRAME);
      Mcv2Format.putU32(page, 8, streamId);
      Mcv2Format.putU32(page, 12, parsed.getFrameId());
      Mcv2Format.putU16(page, 16, number);
      Mcv2Format.putU16(page, 18, count);
      Mcv2Format.putU32(page, 20, parsed.getReferenceId());
      Mcv2Format.putU32(page, 24, frame.length);
      System.arraycopy(frame, from, page, HEADER_BYTES, length);
      final CRC32 crc = new CRC32();
      crc.update(page);
      Mcv2Format.putU32(page, 28, crc.getValue());
      pages.add(toSymbols(page, symbolBits));
    }
    return pages;
  }

  /**
   * The number of useful symbols of a page, derived from its header. A map carries whole rows, so the symbols after
   * this count are padding and not part of the page.
   *
   * @param symbols    at least the header's symbols
   * @param symbolBits the negotiated symbol width
   * @return the useful symbol count
   * @throws Mcv2Exception if the header is truncated, malformed or inconsistent
   */
  public static int usefulSymbols(final byte[] symbols, final int symbolBits) throws Mcv2Exception {
    final byte[] header = readHeader(symbols, symbolBits);
    final int size = checkHeader(header, symbolBits);
    return (int) (((long) (HEADER_BYTES + size) * 8 + symbolBits - 1) / symbolBits);
  }

  private static byte[] readHeader(final byte[] symbols, final int symbolBits) throws Mcv2Exception {
    Preconditions.checkNotNull(symbols, "Symbols must not be null");
    if (symbols.length > PAGE_SYMBOLS) {
      throw new Mcv2Exception("Oversize map page");
    }
    if (symbolBits < 6 || symbolBits > 8) {
      throw new Mcv2Exception("Truncated page header");
    }
    final int headerSymbols = (HEADER_BYTES * 8 + symbolBits - 1) / symbolBits;
    if (symbols.length < headerSymbols) {
      throw new Mcv2Exception("Truncated page header");
    }
    final byte[] header = new byte[HEADER_BYTES];
    long buffer = 0;
    int held = 0;
    int out = 0;
    // the header symbols carry fewer than HEADER_BYTES * 8 + symbolBits bits, so exactly HEADER_BYTES bytes fill
    for (int i = 0; i < headerSymbols; i++) {
      // the reference keeps only the low symbolBits bits of every header symbol
      buffer |= (long) (symbols[i] & ((1 << symbolBits) - 1)) << held;
      held += symbolBits;
      while (held >= 8) {
        header[out++] = (byte) buffer;
        buffer >>>= 8;
        held -= 8;
      }
    }
    return header;
  }

  /** Validates the header fields and returns this page's payload length. */
  private static int checkHeader(final byte[] header, final int symbolBits) throws Mcv2Exception {
    final int capacity = capacity(symbolBits);
    if (Mcv2Format.u32(header, 0) != MAGIC || header[4] != 1 || (header[5] & 0xFF) != symbolBits || Mcv2Format.u16(header, 6) > 1) {
      throw new Mcv2Exception("Unsupported page header");
    }
    final long total = Mcv2Format.u32(header, 24);
    final int number = Mcv2Format.u16(header, 16);
    final int count = Mcv2Format.u16(header, 18);
    if (
      total < Mcv2Format.HEADER_BYTES || total > Mcv2Format.MAX_FRAME_BYTES || count != (total + capacity - 1) / capacity || number >= count
    ) {
      throw new Mcv2Exception("Invalid page metadata");
    }
    return (int) Math.min(capacity, total - (long) number * capacity);
  }

  /**
   * Validates a page before it is stored, exactly as the reference's {@code read_page}: the symbols must be exactly
   * the page's useful symbols, every symbol inside the alphabet, the padding zero and the CRC correct.
   *
   * @param symbols    the page's useful symbols
   * @param symbolBits the negotiated symbol width
   * @return the page
   * @throws Mcv2Exception if the page is malformed or its CRC does not match
   */
  public static TransportPage readPage(final byte[] symbols, final int symbolBits) throws Mcv2Exception {
    final byte[] header = readHeader(symbols, symbolBits);
    final int size = checkHeader(header, symbolBits);
    final byte[] raw = fromSymbols(symbols, symbolBits, HEADER_BYTES + size);
    final long stored = Mcv2Format.u32(raw, 28);
    Mcv2Format.putU32(raw, 28, 0);
    final CRC32 crc = new CRC32();
    crc.update(raw);
    if (crc.getValue() != stored) {
      throw new Mcv2Exception("Page CRC mismatch");
    }
    final byte[] payload = new byte[size];
    System.arraycopy(raw, HEADER_BYTES, payload, 0, size);
    return new TransportPage(
      Mcv2Format.u32(raw, 8),
      Mcv2Format.u32(raw, 12),
      Mcv2Format.u16(raw, 16),
      Mcv2Format.u16(raw, 18),
      Mcv2Format.u32(raw, 20),
      (int) Mcv2Format.u32(raw, 24),
      Mcv2Format.u16(raw, 6),
      symbolBits,
      payload
    );
  }

  /**
   * The reference's conservative, uncompressed Map Data wire model: every page is sent as whole 128-symbol rows plus
   * a fixed per-packet envelope.
   *
   * @param pages          the pages, each as its useful symbols
   * @param fullMaps       whether every page is sent as a complete map instead of whole rows
   * @param packetOverhead the bytes charged per packet
   * @return the modeled wire bytes
   */
  public static long wireBytes(final List<byte[]> pages, final boolean fullMaps, final int packetOverhead) {
    Preconditions.checkNotNull(pages, "Pages must not be null");
    long total = 0;
    for (final byte[] page : pages) {
      total += (fullMaps ? PAGE_SYMBOLS : ((page.length + 127) / 128) * 128L) + packetOverhead;
    }
    return total;
  }
}
