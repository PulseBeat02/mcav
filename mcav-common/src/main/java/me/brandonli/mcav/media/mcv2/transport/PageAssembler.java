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

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Collects transport pages into complete frames, like the reference's {@code Assembler}: pages may arrive in any
 * order and identical duplicates are accepted, but pages that disagree about their frame invalidate it, and at most
 * four frames are pending at once, the oldest being evicted first.
 *
 * <p>The assembler never advances a reference; it only returns frames that are complete and valid. Instances are not
 * thread-safe.
 */
public final class PageAssembler {

  /** Frames that may be pending at once. */
  public static final int MAX_PENDING = 4;

  private final long streamId;

  private final int symbolBits;

  private final Map<Long, Map<Integer, TransportPage>> pending = new LinkedHashMap<>();

  /**
   * Constructs a new assembler.
   *
   * @param streamId   the stream whose pages are accepted
   * @param symbolBits the negotiated symbol width
   */
  public PageAssembler(final long streamId, final int symbolBits) {
    TransportPages.checkSymbolBits(symbolBits);
    this.streamId = streamId;
    this.symbolBits = symbolBits;
  }

  /**
   * Accepts one page.
   *
   * @param symbols the page's useful symbols
   * @return the complete frame's bytes once its last page arrives, otherwise null
   * @throws Mcv2Exception if the page is invalid, belongs to another stream, contradicts the other pages of its frame,
   *                       or completes a frame that is not valid
   */
  public byte@Nullable[] push(final byte[] symbols) throws Mcv2Exception {
    final TransportPage page = TransportPages.readPage(symbols, this.symbolBits);
    if (page.getStreamId() != this.streamId) {
      throw new Mcv2Exception("Wrong stream");
    }
    final long frameId = page.getFrameId();
    if (!this.pending.containsKey(frameId) && this.pending.size() >= MAX_PENDING) {
      final Iterator<Long> oldest = this.pending.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    final Map<Integer, TransportPage> parts = this.pending.computeIfAbsent(frameId, _ -> new HashMap<>());
    if (!parts.isEmpty()) {
      final TransportPage first = parts.values().iterator().next();
      final boolean consistent =
        page.getCount() == first.getCount() &&
        page.getReferenceId() == first.getReferenceId() &&
        page.getFrameBytes() == first.getFrameBytes() &&
        page.getFlags() == first.getFlags();
      if (!consistent) {
        this.pending.remove(frameId);
        throw new Mcv2Exception("Inconsistent pages");
      }
    }
    final TransportPage previous = parts.get(page.getNumber());
    if (previous != null && !previous.equals(page)) {
      this.pending.remove(frameId);
      throw new Mcv2Exception("Conflicting page duplicate");
    }
    parts.put(page.getNumber(), page);
    if (parts.size() != page.getCount()) {
      return null;
    }
    // every page number is below the count and the count agrees across pages, so the numbers are exactly 0..count-1
    final TransportPage[] ordered = new TransportPage[page.getCount()];
    for (final TransportPage part : parts.values()) {
      ordered[part.getNumber()] = part;
    }
    final ByteArrayOutputStream frame = new ByteArrayOutputStream(page.getFrameBytes());
    for (final TransportPage part : ordered) {
      frame.writeBytes(part.payload());
    }
    this.pending.remove(frameId);
    final byte[] data = frame.toByteArray();
    final Mcv2Frame parsed = FrameParser.parse(data);
    if (parsed.getFrameId() != frameId || parsed.getReferenceId() != page.getReferenceId() || (parsed.getFlags() & 1) != page.getFlags()) {
      throw new Mcv2Exception("Frame and page identity mismatch");
    }
    return data;
  }

  /**
   * Gets the number of frames waiting for pages.
   *
   * @return the pending frame count
   */
  public int getPendingCount() {
    return this.pending.size();
  }
}
