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
 * <p>A screen's stream is encoded once for all its viewers, so a viewer whose connection cannot keep up must not hold
 * the others back, nor let a growing backlog of video delay its own game packets. A frame therefore reaches a viewer
 * only when both hold: the viewer can decode it - it is a keyframe, or a P frame whose reference is the last frame or
 * the last keyframe the viewer was sent, which are the pictures its client holds - and the video handed to the
 * viewer's connection and not yet written is at most the backlog limit. A viewer over the limit misses frames until
 * one it can decode comes with its backlog under the limit again: the next frame when P frames predict from the last
 * keyframe, the next keyframe when each predicts from the frame before. Its client keeps the last picture it decoded
 * meanwhile. No frame whose reference the viewer lacks is ever sent: its client could not decode it anyway, and it
 * would only add to the backlog.
 *
 * <p>Offers come from the thread that sends frames, one at a time; writes complete on the connection's thread.
 */
public final class Mcv2Link {

  /** The id no frame has: frame ids are unsigned 32-bit numbers. */
  private static final long NONE = -1;

  private final long limit;
  private final AtomicLong backlog = new AtomicLong();
  private long lastFrame = NONE;
  private long lastKeyframe = NONE;
  private long sent;
  private long behind;
  private long undecodable;

  /**
   * Constructs the link of a viewer that has decoded nothing yet.
   *
   * @param limit the most video bytes the viewer's connection may have left to write for another frame to be sent
   * @throws IllegalArgumentException if the limit is negative
   */
  public Mcv2Link(final long limit) {
    Preconditions.checkArgument(limit >= 0, "Backlog limit must not be negative");
    this.limit = limit;
  }

  /**
   * Checks whether the viewer's client could decode a frame: a keyframe, or a P frame predicting from the last frame
   * or the last keyframe the viewer was sent.
   *
   * @param keyframe    whether the frame is a keyframe
   * @param referenceId the id of the frame a P frame predicts from
   * @return true if the viewer holds what the frame needs
   */
  public synchronized boolean canDecode(final boolean keyframe, final long referenceId) {
    return keyframe || (referenceId != NONE && (referenceId == this.lastFrame || referenceId == this.lastKeyframe));
  }

  /**
   * Decides whether a frame goes to the viewer now, and when it does, records it as sent and adds its bytes to the
   * backlog; {@link #written(long)} takes them off again.
   *
   * @param frameId     the frame's id
   * @param referenceId the id of the frame a P frame predicts from
   * @param keyframe    whether the frame is a keyframe
   * @param bytes       the video bytes the frame puts on the viewer's connection
   * @return true if the frame is to be sent to the viewer
   */
  public synchronized boolean offer(final long frameId, final long referenceId, final boolean keyframe, final long bytes) {
    Preconditions.checkArgument(frameId >= 0 && frameId <= 0xFFFFFFFFL, "Frame id must be an unsigned 32-bit value");
    Preconditions.checkArgument(bytes >= 0, "Bytes must not be negative");
    if (this.backlog.get() > this.limit) {
      this.behind++;
      return false;
    }
    if (!this.canDecode(keyframe, referenceId)) {
      this.undecodable++;
      return false;
    }
    this.lastFrame = frameId;
    if (keyframe) {
      this.lastKeyframe = frameId;
    }
    this.backlog.addAndGet(bytes);
    this.sent++;
    return true;
  }

  /**
   * Takes a sent frame's bytes off the backlog once its write completed or failed.
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
