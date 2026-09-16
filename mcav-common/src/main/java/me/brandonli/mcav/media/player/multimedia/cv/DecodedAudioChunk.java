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

/**
 * A chunk of decoded audio on its way from the decoding thread to the rendering thread. The samples are signed
 * 16-bit little-endian stereo at 48 kHz, which is the format every audio filter expects.
 */
final class DecodedAudioChunk {

  private final ByteBuffer samples;
  private final long timestampMicros;

  DecodedAudioChunk(final ByteBuffer samples, final long timestampMicros) {
    this.samples = samples;
    this.timestampMicros = timestampMicros;
  }

  ByteBuffer getSamples() {
    return this.samples;
  }

  long getTimestampMicros() {
    return this.timestampMicros;
  }
}
