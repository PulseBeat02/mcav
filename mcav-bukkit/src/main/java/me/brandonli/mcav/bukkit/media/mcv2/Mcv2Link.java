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

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One viewer's side of an MCV2 stream: which frames the viewer can decode, and how much video its connection has not
 * yet written.
 *
 * <p>A screen is encoded once for all viewers. Each viewer receives a keyframe or a P frame referencing
 * the last frame it was sent, provided its pending video bytes are within the backlog limit. Keyframes
 * have {@link #KEYFRAME_ALLOWANCE} times that allowance. After missing a frame, a viewer waits for a
 * decodable keyframe and keeps displaying its previous picture meanwhile.
 *
 * <p>Offers come from the thread that sends frames, one at a time; writes complete on the connection's thread.
 */
public final class Mcv2Link {

  /** How many times the backlog limit a keyframe may still go out over. */
  public static final int KEYFRAME_ALLOWANCE = 2;

  private static final long NONE = -1;

  private final long limit;

  private final AtomicLong backlog = new AtomicLong();

  private long lastFrame = NONE;

  private long sent;

  private long behind;

  private long undecodable;

  /**
   * Constructs the link of a viewer that has decoded nothing yet.
   *
   * @param limit the nonnegative backlog threshold in estimated bytes before accepting another frame;
   *              zero admits a frame only when the tracked backlog is zero
   * @throws IllegalArgumentException if the limit is negative
   */
  public Mcv2Link(final long limit) {
    Preconditions.checkArgument(limit >= 0, "Backlog limit must not be negative");
    this.limit = limit;
  }

  /**
   * Checks whether a frame is independent or predicts from the last frame this viewer was sent.
   *
   * @param isKeyframe  whether the frame is a keyframe
   * @param referenceId the id of the frame a P frame predicts from
   * @return true if the viewer holds what the frame needs
   */
  public synchronized boolean canDecode(final boolean isKeyframe, final long referenceId) {
    return isKeyframe || (referenceId != NONE && referenceId == this.lastFrame);
  }

  /**
   * Decides whether a frame goes to the viewer now, and when it does, records it as sent and adds its bytes to the
   * backlog; {@link #written(long)} takes them off again.
   *
   * <p>The threshold tests the existing backlog before adding this frame, so an accepted frame can take the
   * backlog above the threshold. Send every accepted frame and balance its byte charge with {@link #written(long)}.
   *
   * @param frameId     the frame's id
   * @param referenceId the id of the frame a P frame predicts from
   * @param isKeyframe  whether the frame is a keyframe
   * @param bytes       the video bytes the frame puts on the viewer's connection
   * @return true if the frame is to be sent to the viewer
   * @throws IllegalArgumentException if frameId is outside unsigned 32-bit range or bytes is negative
   */
  public synchronized boolean offer(final long frameId, final long referenceId, final boolean isKeyframe, final long bytes) {
    Preconditions.checkArgument(frameId >= 0 && frameId <= Mcv2Decoder.MAX_U32, "Frame id must be an unsigned 32-bit value");
    Preconditions.checkArgument(bytes >= 0, "Bytes must not be negative");
    if (this.backlog.get() > (isKeyframe ? allowance(this.limit) : this.limit)) {
      this.behind++;
      return false;
    }
    if (!this.canDecode(isKeyframe, referenceId)) {
      this.undecodable++;
      return false;
    }
    this.lastFrame = frameId;
    this.backlog.addAndGet(bytes);
    this.sent++;
    return true;
  }

  static long allowance(final long limit) {
    return limit > Long.MAX_VALUE / KEYFRAME_ALLOWANCE ? Long.MAX_VALUE : limit * KEYFRAME_ALLOWANCE;
  }

  /**
   * Takes a sent frame's bytes off the backlog once its write completed or failed.
   *
   * <p>Call exactly once for each charged byte, including failed writes. Partial bundle completions may split
   * a frame's charge. Nonnegative amounts and balanced accounting are caller preconditions, not checked here.
   *
   * @param bytes the bytes {@link #offer} added for the frame
   */
  public void written(final long bytes) {
    this.backlog.addAndGet(-bytes);
  }

  /**
   * Gets the video bytes handed to the viewer's connection and not yet written.
   *
   * @return the backlog
   */
  public long getBacklog() {
    return this.backlog.get();
  }

  /**
   * Gets how many frames the viewer was sent.
   *
   * @return the frames sent
   */
  public synchronized long getSent() {
    return this.sent;
  }

  /**
   * Gets how many frames the viewer missed because its connection was over the backlog limit.
   *
   * @return the frames missed for the backlog
   */
  public synchronized long getBehind() {
    return this.behind;
  }

  /**
   * Gets how many frames the viewer missed because it did not hold their reference, after missing one before.
   *
   * @return the frames missed for their reference
   */
  public synchronized long getUndecodable() {
    return this.undecodable;
  }
}
