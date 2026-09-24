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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that queued audio retains its presentation timestamp, including preroll. */
final class DecodedAudioChunkTest {

  @ParameterizedTest
  @ValueSource(longs = { -125_000L, 987_654_321L })
  void retainsThePresentationTimestamp(final long timestamp) {
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final DecodedAudioChunk chunk = new DecodedAudioChunk(samples, timestamp);
    final long actual = chunk.getTimestampMicros();
    assertEquals(timestamp, actual);
  }
}
