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

/**
 * Plays audio at another speed by resampling it, which keeps it in step with pictures shown at that speed: at twice
 * the speed a chunk becomes half as long and sounds an octave higher. Between two frames the samples are
 * interpolated linearly. Where the next frame falls and the last frame of a chunk carry over to the next chunk, so
 * the chunks join without a click.
 *
 * <p>Not thread-safe: the audio renderer of one session uses it.
 */
final class SpeedResampler {

  private final short[] previous;

  /** Where the next frame lies, in frames, counted from the last frame of the chunk before, which is frame 0. */
  private double next;

  SpeedResampler() {
    this.previous = new short[AudioFilter.CHANNELS];
    this.next = 1;
  }

  /**
   * Resamples a chunk of 16-bit little-endian frames of {@link AudioFilter#CHANNELS} channels.
   *
   * @param samples the frames, from the position to the limit of the buffer, which is not consumed
   * @param speed   how fast the chunk plays, positive
   * @return the chunk itself at normal speed, otherwise a new chunk about {@code 1 / speed} as long, without a
   *     trailing partial frame
   */
  ByteBuffer resample(final ByteBuffer samples, final double speed) {
    final ByteBuffer input = samples.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    final int frames = input.remaining() / AudioFilter.FRAME_SIZE;
    if (frames == 0) {
      return samples;
    }
    if (speed == PlaybackClock.NORMAL_SPEED) {
      this.remember(input, frames);
      this.next = 1;
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

  /** A sample of a frame: frame 0 is the last frame of the chunk before, frame n the chunk's frame n - 1. */
  private int sample(final ByteBuffer input, final int frame, final int channel) {
    if (frame == 0) {
      return this.previous[channel];
    }
    return input.getShort(offset(input, frame - 1, channel));
  }

  private void remember(final ByteBuffer input, final int frames) {
    for (int channel = 0; channel < AudioFilter.CHANNELS; channel++) {
      this.previous[channel] = input.getShort(offset(input, frames - 1, channel));
    }
  }

  private static int offset(final ByteBuffer input, final int frame, final int channel) {
    return input.position() + (frame * AudioFilter.CHANNELS + channel) * AudioFilter.BYTES_PER_SAMPLE;
  }
}
