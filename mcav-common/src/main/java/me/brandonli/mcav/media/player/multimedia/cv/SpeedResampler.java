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
package me.brandonli.mcav.media.player.multimedia.cv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

// Resamples audio to the playback speed, so it keeps in step with the pictures and its pitch follows the speed. The
// next frame position and a chunk's last frame carry over to the next chunk, so chunks join without a click. Not
// thread-safe.
final class SpeedResampler {

  private static final double FIRST_FRAME_POSITION = 1.0;

  private final short[] previous;

  private double next;

  SpeedResampler() {
    this.previous = new short[AudioFilter.CHANNELS];
    this.next = FIRST_FRAME_POSITION;
  }

  ByteBuffer resample(final ByteBuffer samples, final double speed) {
    final ByteBuffer input = samples.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    final int frames = input.remaining() / AudioFilter.FRAME_SIZE;
    if (frames == 0) {
      return samples;
    }

    if (speed == PlaybackClock.NORMAL_SPEED) {
      this.remember(input, frames);
      this.next = FIRST_FRAME_POSITION;

      return samples;
    }

    final int capacity = (int) Math.ceil(frames / speed) + 1;
    final ByteBuffer output = ByteBuffer.allocate(capacity * AudioFilter.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    double at = this.next;
    while (at < frames) {
      final int frame = (int) at;
      final double fraction = at - frame;
      for (int channel = 0; channel < AudioFilter.CHANNELS; channel++) {
        final int from = this.sample(input, frame, channel);
        final int to = this.sample(input, frame + 1, channel);
        output.putShort((short) Math.round(from + (to - from) * fraction));
      }
      at += speed;
    }

    this.next = at - frames;
    this.remember(input, frames);

    return output.flip();
  }

  private int sample(final ByteBuffer input, final int frame, final int channel) {
    if (frame == 0) {
      return this.previous[channel];
    }

    return input.getShort(this.offset(input, frame - 1, channel));
  }

  private void remember(final ByteBuffer input, final int frames) {
    for (int channel = 0; channel < AudioFilter.CHANNELS; channel++) {
      this.previous[channel] = input.getShort(this.offset(input, frames - 1, channel));
    }
  }

  private int offset(final ByteBuffer input, final int frame, final int channel) {
    return input.position() + (frame * AudioFilter.CHANNELS + channel) * AudioFilter.BYTES_PER_SAMPLE;
  }
}
