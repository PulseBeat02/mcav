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
package me.brandonli.mcav.utils.audio;

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

/**
 * Mixes the interleaved 16-bit little-endian stereo audio that the players hand to audio pipelines down to mono by
 * averaging both channels. Outputs that carry a single channel, such as Simple Voice Chat, a mono speaker or a
 * speech recognizer, use it to turn the samples of the pipeline into the format they expect.
 *
 * <pre>{@code
 * final AudioPipelineStep pipeline = AudioPipelineStep.of((samples, metadata) -> {
 *   final short[] mono = MonoDownmixer.downmix(samples);
 *   speaker.play(mono);
 *   // the samples were only read, so the filter reports that it left them untouched
 *   return false;
 * });
 * }</pre>
 */
public final class MonoDownmixer {

  private MonoDownmixer() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Mixes stereo samples down to mono.
   *
   * @param stereo little-endian interleaved 16-bit stereo samples, read from position to limit without changing
   *               the buffer; a trailing incomplete sample is ignored
   * @return one mono sample for every stereo sample
   */
  public static short[] downmix(final ByteBuffer stereo) {
    Preconditions.checkNotNull(stereo, "Samples must not be null");
    final ByteBuffer source = stereo.duplicate();
    source.order(ByteOrder.LITTLE_ENDIAN);
    final int frames = source.remaining() / AudioFilter.FRAME_SIZE;
    final short[] mono = new short[frames];
    for (int frame = 0; frame < frames; frame++) {
      final short left = source.getShort();
      final short right = source.getShort();
      final int sum = left + right;
      mono[frame] = (short) (sum >> 1);
    }
    return mono;
  }
}
