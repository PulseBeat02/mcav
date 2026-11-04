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
package me.brandonli.mcav.bukkit.media.mcv2;

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The reference state machine of a receiver, like the reference's {@code Decoder}: it commits a frame atomically only
 * when the frame is newer than the last one and decodes against it, and otherwise throws without changing its state,
 * so a lost frame freezes the picture until the next keyframe.
 *
 * <p>Frame ids wrap modulo 2^32; a frame is newer when {@code 0 < (new - last) mod 2^32 < 2^31}. Instances are not
 * thread-safe.
 *
 * <p>This receiver retains only the immediately preceding decoded picture. A stream referencing an older
 * keyframe needs a caller-managed reference strategy with {@link Mcv2Decoder}, even if that keyframe was received.
 *
 * @since 1.0.0
 */
public final class Mcv2Receiver {

  private byte @Nullable [] reference;

  private static final long NO_FRAME_ID = -1;

  private long frameId;

  private int width;

  private int height;

  /**
   * Creates a receiver that holds no frame yet, so it accepts a keyframe first.
   *
   * @since 1.0.0
   */
  public Mcv2Receiver() {
    this.frameId = NO_FRAME_ID;
  }

  /**
   * Validates, decodes and commits a frame.
   *
   * @param data the frame bytes
   * @return the decoded row-major RGB picture, retained as this receiver's next reference; do not mutate
   *         it while the receiver may use it, and copy it for independently mutable storage
   * @throws Mcv2Exception        if the frame is invalid, not newer than the last one, or predicts from a frame this receiver
   *                              does not hold; the state is unchanged then
   * @throws NullPointerException if the frame bytes are null
   * @since 1.0.0
   */
  public byte[] accept(final byte[] data) {
    final Frame frame = Mcv2Decoder.parse(data);
    if (this.frameId >= 0 && !Mcv2Decoder.follows(frame.getFrameId(), this.frameId)) {
      final String message = "Stale or ambiguous frame number!";
      throw new Mcv2Exception(message);
    }

    if (!frame.isKeyframe() && (frame.getWidth() != this.width || frame.getHeight() != this.height)) {
      final String message = "Reference frame dimensions mismatch!";
      throw new Mcv2Exception(message);
    }

    final byte[] result = Mcv2Decoder.decode(frame, this.reference, this.frameId);
    this.reference = result;
    this.frameId = frame.getFrameId();
    this.width = frame.getWidth();
    this.height = frame.getHeight();

    return result;
  }

  /**
   * Returns the id of the last committed frame.
   *
   * @return the frame id, or -1 before the first frame
   * @since 1.0.0
   */
  public long getFrameId() {
    return this.frameId;
  }
}
