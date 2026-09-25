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
package me.brandonli.mcav.media.mcv2;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The reference state machine of a receiver, like the reference's {@code Decoder}: it commits a frame atomically only
 * when the frame is newer than the last one and decodes against it, and otherwise throws without changing its state,
 * so a lost frame freezes the picture until the next keyframe.
 *
 * <p>Frame ids wrap modulo 2^32; a frame is newer when {@code 0 < (new - last) mod 2^32 < 2^31}. Instances are not
 * thread-safe.
 */
public final class Mcv2Receiver {

  private byte@Nullable[] reference;
  private long frameId = -1;

  /**
   * Constructs a receiver that holds no frame yet, so it accepts a keyframe first.
   */
  public Mcv2Receiver() {
    // the first accepted frame sets the state
  }

  /**
   * Validates, decodes and commits a frame.
   *
   * @param data the frame bytes
   * @return the decoded picture
   * @throws Mcv2Exception if the frame is invalid, not newer than the last one, or predicts from a frame this receiver
   *                       does not hold; the state is unchanged then
   */
  public byte[] accept(final byte[] data) throws Mcv2Exception {
    final Mcv2Frame frame = FrameParser.parse(data);
    if (this.frameId >= 0) {
      final long distance = (frame.getFrameId() - this.frameId) & 0xFFFFFFFFL;
      if (distance == 0 || distance >= 0x80000000L) {
        throw new Mcv2Exception("Stale or ambiguous frame number");
      }
    }
    final byte[] result = Mcv2Decoder.decode(frame, this.reference, this.frameId);
    this.reference = result;
    this.frameId = frame.getFrameId();
    return result;
  }

  /**
   * Gets the id of the last committed frame.
   *
   * @return the frame id, or -1 before the first frame
   */
  public long getFrameId() {
    return this.frameId;
  }
}
