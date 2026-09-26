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
package me.brandonli.mcav.sandbox.command.video;

import com.google.common.base.Preconditions;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.media.mcv2.Mcv2Format;

/**
 * Plays an encoded MCV2 stream on a screen, one frame per run, looping. A stream cannot make a keyframe on request, so
 * when the channel asks for one, for a viewer who just started watching, playback starts over from the first frame,
 * which is a keyframe. Frame and reference ids are shifted so they keep increasing across every start and loop:
 * clients only decode frames newer than the last one they decoded.
 */
final class Mcv2Playback implements Runnable {

  private final Mcv2Channel channel;

  private final List<byte[]> frames;

  private final long span;

  private int next;

  private long offset;

  /**
   * Constructs a playback.
   *
   * @param channel the screen's channel
   * @param frames  the stream's frames, starting with a keyframe
   */
  Mcv2Playback(final Mcv2Channel channel, final List<byte[]> frames) {
    Preconditions.checkNotNull(channel, "Channel must not be null");
    Preconditions.checkArgument(!frames.isEmpty(), "A stream has at least one frame");
    this.channel = channel;
    this.frames = List.copyOf(frames);
    long highest = 0;
    for (final byte[] frame : this.frames) {
      highest = Math.max(highest, Mcv2Format.u32(frame, Mcv2Format.FRAME_ID_OFFSET));
    }
    this.span = highest + 1;
  }

  /**
   * Sends the next frame to the viewers that watch, if there are any.
   */
  @Override
  public void run() {
    this.channel.update();
    if (this.channel.takeKeyframeRequest() && this.next != 0) {
      this.next = 0;
      this.offset += this.span;
    }
    if (this.channel.getRecipients().isEmpty()) {
      return;
    }
    this.channel.send(this.shifted(this.frames.get(this.next)));
    this.next++;
    if (this.next == this.frames.size()) {
      this.next = 0;
      this.offset += this.span;
    }
  }

  /** A copy of a frame with its frame and reference ids shifted by the current offset. */
  private byte[] shifted(final byte[] frame) {
    final byte[] copy = frame.clone();
    shift(copy, Mcv2Format.FRAME_ID_OFFSET);
    shift(copy, Mcv2Format.REFERENCE_ID_OFFSET);
    return copy;
  }

  private void shift(final byte[] frame, final int field) {
    Mcv2Format.putU32(frame, field, (Mcv2Format.u32(frame, field) + this.offset) & Mcv2Format.MAX_U32);
  }
}
