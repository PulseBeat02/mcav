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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;

// a stream cannot make a keyframe on request, so a new viewer restarts playback from the first frame
final class Mcv2Playback implements Runnable {

  private final Mcv2Channel channel;
  private final List<byte[]> frames;
  private final long span;

  private int next;
  private long offset;
  private boolean stopped;

  // the frames start with a keyframe
  Mcv2Playback(final Mcv2Channel channel, final List<byte[]> frames, final long firstFrameId) {
    Preconditions.checkNotNull(channel, "Channel must not be null!");
    Preconditions.checkArgument(!frames.isEmpty(), "A stream has at least one frame!");

    this.channel = channel;
    this.frames = List.copyOf(frames);

    long highest = 0;
    for (final byte[] frame : this.frames) {
      final long frameId = Mcv2Decoder.readUnsignedInt(frame, Mcv2Decoder.FRAME_ID_OFFSET);
      highest = Math.max(highest, frameId);
    }
    this.span = highest + 1;
    this.offset = firstFrameId;
  }

  // waits for the frame being sent, so none is sent once this returns
  synchronized void stop() {
    this.stopped = true;
  }

  @Override
  public synchronized void run() {
    if (this.stopped) {
      return;
    }

    this.channel.update();

    if (this.channel.takeKeyframeRequest() && this.next != 0) {
      this.restartFromFirstFrame();
    }

    final Collection<UUID> recipients = this.channel.getRecipients();
    if (recipients.isEmpty()) {
      return;
    }

    final byte[] frame = this.frames.get(this.next);
    final byte[] shifted = this.copyWithShiftedIds(frame);
    this.channel.send(shifted);
    this.next++;

    if (this.next == this.frames.size()) {
      this.restartFromFirstFrame();
    }
  }

  private void restartFromFirstFrame() {
    this.next = 0;
    this.offset += this.span;
  }

  private byte[] copyWithShiftedIds(final byte[] frame) {
    final byte[] copy = frame.clone();
    this.shiftId(copy, Mcv2Decoder.FRAME_ID_OFFSET);
    this.shiftId(copy, Mcv2Decoder.REFERENCE_ID_OFFSET);
    return copy;
  }

  private void shiftId(final byte[] frame, final int fieldOffset) {
    final long id = Mcv2Decoder.readUnsignedInt(frame, fieldOffset);
    final long shiftedId = (id + this.offset) & Mcv2Decoder.MAXIMUM_UNSIGNED_INT;
    Mcv2Decoder.putUnsignedInt(frame, fieldOffset, shiftedId);
  }
}
