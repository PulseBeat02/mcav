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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class SpeedResamplerTest {

  /** Frames of two channels: the left samples count up by 10, the right ones down. */
  private static ByteBuffer frames(final int first, final int count) {
    final ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int frameIndex = 0; frameIndex < count; frameIndex++) {
      buffer.putShort((short) ((first + frameIndex) * 10));
      buffer.putShort((short) (-(first + frameIndex) * 10));
    }
    return buffer.flip();
  }

  private static short[] left(final ByteBuffer buffer) {
    final ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    final short[] samples = new short[view.remaining() / 4];
    for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
      samples[sampleIndex] = view.getShort(view.position() + sampleIndex * 4);
      assertEquals(-samples[sampleIndex], view.getShort(view.position() + sampleIndex * 4 + 2), "the channels are resampled alike");
    }
    return samples;
  }

  @Test
  void passesChunksThroughAtNormalSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    final ByteBuffer chunk = frames(0, 4);
    assertSame(chunk, resampler.resample(chunk, PlaybackClock.NORMAL_SPEED));
    final ByteBuffer empty = ByteBuffer.allocate(3);
    assertSame(empty, resampler.resample(empty, 2));
  }

  @Test
  void halvesAChunkAtTwiceTheSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    assertArrayEquals(new short[] { 0, 20, 40, 60 }, left(resampler.resample(frames(0, 8), 2)));
    assertArrayEquals(new short[] { 80, 100, 120, 140 }, left(resampler.resample(frames(8, 8), 2)));
  }

  @Test
  void interpolatesAtHalfTheSpeedAcrossChunks() {
    final SpeedResampler resampler = new SpeedResampler();
    assertArrayEquals(new short[] { 0, 5, 10, 15 }, left(resampler.resample(frames(0, 3), 0.5)));
    assertArrayEquals(new short[] { 20, 25, 30, 35 }, left(resampler.resample(frames(3, 2), 0.5)));
  }

  @Test
  void startsAfterTheLastFrameOfAChunkPlayedAtNormalSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    resampler.resample(frames(0, 2), 0.5);
    resampler.resample(frames(2, 2), PlaybackClock.NORMAL_SPEED);
    assertArrayEquals(new short[] { 40, 45 }, left(resampler.resample(frames(4, 2), 0.5)));
    assertArrayEquals(new short[] { 50, 55, 60, 65 }, left(resampler.resample(frames(6, 2), 0.5)));
  }

  @Test
  void resamplesFromThePositionAndLeavesATrailingPartialFrame() {
    final SpeedResampler resampler = new SpeedResampler();
    final ByteBuffer chunk = ByteBuffer.allocate(4 + 4 * 4 + 3).order(ByteOrder.LITTLE_ENDIAN);
    chunk.putInt(0x7fff7fff);
    chunk.put(frames(0, 4));
    chunk.put(new byte[3]);
    chunk.flip().position(4);
    final ByteBuffer faster = resampler.resample(chunk, 2);
    assertArrayEquals(new short[] { 0, 20 }, left(faster));
    assertEquals(4, chunk.position());
  }
}
