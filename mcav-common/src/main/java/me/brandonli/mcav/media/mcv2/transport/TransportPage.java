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

import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One validated transport page: its header fields and its share of the frame's bytes.
 *
 * <p>Instances are immutable. Two pages are equal when every header field and every payload byte is equal, which is
 * what the assembler uses to accept an identical duplicate and reject a conflicting one.
 */
public final class TransportPage {

  private final long streamId;
  private final long frameId;
  private final int number;
  private final int count;
  private final long referenceId;
  private final int frameBytes;
  private final int flags;
  private final int symbolBits;
  private final byte[] payload;

  TransportPage(
    final long streamId,
    final long frameId,
    final int number,
    final int count,
    final long referenceId,
    final int frameBytes,
    final int flags,
    final int symbolBits,
    final byte[] payload
  ) {
    this.streamId = streamId;
    this.frameId = frameId;
    this.number = number;
    this.count = count;
    this.referenceId = referenceId;
    this.frameBytes = frameBytes;
    this.flags = flags;
    this.symbolBits = symbolBits;
    this.payload = payload;
  }

  /**
   * Gets the stream id.
   *
   * @return the unsigned 32-bit stream id
   */
  public long getStreamId() {
    return this.streamId;
  }

  /**
   * Gets the id of the frame this page belongs to.
   *
   * @return the unsigned 32-bit frame id
   */
  public long getFrameId() {
    return this.frameId;
  }

  /**
   * Gets the zero-based number of this page inside its frame.
   *
   * @return the page number
   */
  public int getNumber() {
    return this.number;
  }

  /**
   * Gets the number of pages of the frame.
   *
   * @return the page count
   */
  public int getCount() {
    return this.count;
  }

  /**
   * Gets the reference id of the frame.
   *
   * @return the unsigned 32-bit reference id
   */
  public long getReferenceId() {
    return this.referenceId;
  }

  /**
   * Gets the length of the complete frame.
   *
   * @return the frame length in bytes
   */
  public int getFrameBytes() {
    return this.frameBytes;
  }

  /**
   * Gets the page flags: 1 for a keyframe, 0 for a P frame.
   *
   * @return the flags
   */
  public int getFlags() {
    return this.flags;
  }

  /**
   * Gets the number of useful bits per symbol.
   *
   * @return 6, 7 or 8
   */
  public int getSymbolBits() {
    return this.symbolBits;
  }

  /**
   * Gets a copy of this page's share of the frame bytes.
   *
   * @return the payload
   */
  public byte[] getPayload() {
    return this.payload.clone();
  }

  byte[] payload() {
    return this.payload;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (!(other instanceof final TransportPage page)) {
      return false;
    }
    return (
      this.streamId == page.streamId &&
      this.frameId == page.frameId &&
      this.number == page.number &&
      this.count == page.count &&
      this.referenceId == page.referenceId &&
      this.frameBytes == page.frameBytes &&
      this.flags == page.flags &&
      this.symbolBits == page.symbolBits &&
      Arrays.equals(this.payload, page.payload)
    );
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(this.frameId);
    result = 31 * result + this.number;
    result = 31 * result + Arrays.hashCode(this.payload);
    return result;
  }
}
