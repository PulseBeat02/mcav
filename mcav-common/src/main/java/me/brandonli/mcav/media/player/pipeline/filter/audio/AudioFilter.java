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
package me.brandonli.mcav.media.player.pipeline.filter.audio;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.Filter;

/**
 * A step of an audio pipeline that inspects or modifies a buffer of audio samples.
 *
 * <p>Every player delivers audio as signed 16-bit little-endian PCM at 48 kHz with two interleaved channels,
 * regardless of the source, so filters never have to deal with other formats. Filters run on the audio render
 * thread of the player, and the same buffer is passed to every filter of the pipeline: a filter must read from the
 * buffer without changing its position, for example through {@link ByteBuffer#duplicate()}, or restore the
 * position before it returns.
 */
@FunctionalInterface
public interface AudioFilter extends Filter<ByteBuffer, OriginalAudioMetadata> {
  /**
   * A filter that leaves every buffer untouched.
   */
  AudioFilter NO_OP = (_, _) -> false;

  /**
   * The sample rate of the audio every player delivers, in hertz.
   */
  int SAMPLE_RATE = 48_000;

  /**
   * The number of interleaved channels of the audio every player delivers.
   */
  int CHANNELS = 2;

  /**
   * The size of one sample of one channel in bytes.
   */
  int BYTES_PER_SAMPLE = 2;

  /**
   * The size of one sample of every channel in bytes.
   */
  int FRAME_SIZE = CHANNELS * BYTES_PER_SAMPLE;
}
