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

import com.google.common.base.MoreObjects;
import java.util.Arrays;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One validated transport page: its header fields and its share of the frame's bytes.
 *
 * <p>Instances are immutable and thread-safe. Two pages are equal when every header field and every payload byte is
 * equal, which is what the assembler uses to accept an identical duplicate and reject a conflicting one.
 *
 * @since 1.0.0
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

  byte[] payload() {
    return this.payload;
  }

  /**
   * Returns the stream id.
   *
   * @return the unsigned 32-bit stream id
   * @since 1.0.0
   */
  public long getStreamId() {
    return this.streamId;
  }

  /**
   * Returns the id of the frame this page belongs to.
   *
   * @return the unsigned 32-bit frame id
   * @since 1.0.0
   */
  public long getFrameId() {
    return this.frameId;
  }

  /**
   * Returns the zero-based number of this page inside its frame.
   *
   * @return the page number
   * @since 1.0.0
   */
  public int getNumber() {
    return this.number;
  }

  /**
   * Returns the number of pages of the frame.
   *
   * @return the page count
   * @since 1.0.0
   */
  public int getCount() {
    return this.count;
  }

  /**
   * Returns the reference id of the frame.
   *
   * @return the unsigned 32-bit reference id
   * @since 1.0.0
   */
  public long getReferenceId() {
    return this.referenceId;
  }

  /**
   * Returns the length of the complete frame.
   *
   * @return the frame length in bytes
   * @since 1.0.0
   */
  public int getFrameBytes() {
    return this.frameBytes;
  }

  /**
   * Returns the page flags: 1 for a keyframe, 0 for a P frame.
   *
   * @return the flags
   * @since 1.0.0
   */
  public int getFlags() {
    return this.flags;
  }

  /**
   * Returns the number of useful bits per symbol.
   *
   * @return 6
   * @since 1.0.0
   */
  public int getSymbolBits() {
    return this.symbolBits;
  }

  /**
   * Returns a copy of this page's share of the frame bytes.
   *
   * @return the payload
   * @since 1.0.0
   */
  public byte[] getPayload() {
    return this.payload.clone();
  }

  /**
   * Returns whether {@code other} is a page with the same header fields and payload bytes.
   *
   * @param other the object to compare with
   * @return whether every part matches
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

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

  /**
   * Returns a hash code built from the frame id, the page number and the payload bytes.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    final int payloadHash = Arrays.hashCode(this.payload);
    return Objects.hash(this.frameId, this.number, payloadHash);
  }

  /**
   * Returns a debugging representation with every header field and the payload length.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("streamId", this.streamId)
      .add("frameId", this.frameId)
      .add("number", this.number)
      .add("count", this.count)
      .add("referenceId", this.referenceId)
      .add("frameBytes", this.frameBytes)
      .add("flags", this.flags)
      .add("symbolBits", this.symbolBits)
      .add("payloadBytes", this.payload.length)
      .toString();
  }
}
