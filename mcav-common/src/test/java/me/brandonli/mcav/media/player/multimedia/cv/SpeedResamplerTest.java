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

  private static final int FRAME_BYTES = 4;

  private static final double DOUBLE_SPEED = 2.0;
  private static final double HALF_SPEED = 0.5;

  private static ByteBuffer frames(final int first, final int count) {
    final ByteBuffer buffer = ByteBuffer.allocate(count * FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (int frameIndex = 0; frameIndex < count; frameIndex++) {
      buffer.putShort((short) ((first + frameIndex) * 10));
      buffer.putShort((short) (-(first + frameIndex) * 10));
    }

    return buffer.flip();
  }

  private static short[] left(final ByteBuffer buffer) {
    final ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    final short[] samples = new short[view.remaining() / FRAME_BYTES];
    for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
      samples[sampleIndex] = view.getShort(view.position() + sampleIndex * FRAME_BYTES);
      final short right = view.getShort(view.position() + sampleIndex * FRAME_BYTES + 2);
      assertEquals(-samples[sampleIndex], right, "the channels are resampled alike");
    }

    return samples;
  }

  @Test
  void shouldPassChunksThroughAtNormalSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    final ByteBuffer chunk = frames(0, 4);
    final ByteBuffer resampled = resampler.resample(chunk, PlaybackClock.NORMAL_SPEED);
    assertSame(chunk, resampled);
  }

  @Test
  void shouldPassAChunkWithoutAWholeFrameThroughUnchanged() {
    final SpeedResampler resampler = new SpeedResampler();
    final ByteBuffer partialFrame = ByteBuffer.allocate(3);
    final ByteBuffer resampled = resampler.resample(partialFrame, DOUBLE_SPEED);
    assertSame(partialFrame, resampled);
  }

  @Test
  void shouldHalveAChunkAtTwiceTheSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    final short[] firstChunk = left(resampler.resample(frames(0, 8), DOUBLE_SPEED));
    assertArrayEquals(new short[] { 0, 20, 40, 60 }, firstChunk);

    final short[] secondChunk = left(resampler.resample(frames(8, 8), DOUBLE_SPEED));
    assertArrayEquals(new short[] { 80, 100, 120, 140 }, secondChunk);
  }

  @Test
  void shouldInterpolateAtHalfTheSpeedAcrossChunks() {
    final SpeedResampler resampler = new SpeedResampler();
    final short[] firstChunk = left(resampler.resample(frames(0, 3), HALF_SPEED));
    assertArrayEquals(new short[] { 0, 5, 10, 15 }, firstChunk);

    final short[] secondChunk = left(resampler.resample(frames(3, 2), HALF_SPEED));
    assertArrayEquals(new short[] { 20, 25, 30, 35 }, secondChunk);
  }

  @Test
  void shouldStartAfterTheLastFrameOfAChunkPlayedAtNormalSpeed() {
    final SpeedResampler resampler = new SpeedResampler();
    resampler.resample(frames(0, 2), HALF_SPEED);
    resampler.resample(frames(2, 2), PlaybackClock.NORMAL_SPEED);
    final short[] firstChunk = left(resampler.resample(frames(4, 2), HALF_SPEED));
    assertArrayEquals(new short[] { 40, 45 }, firstChunk);

    final short[] secondChunk = left(resampler.resample(frames(6, 2), HALF_SPEED));
    assertArrayEquals(new short[] { 50, 55, 60, 65 }, secondChunk);
  }

  @Test
  void shouldResampleFromThePositionAndLeaveATrailingPartialFrame() {
    final SpeedResampler resampler = new SpeedResampler();
    final ByteBuffer chunk = ByteBuffer.allocate(4 + 4 * FRAME_BYTES + 3).order(ByteOrder.LITTLE_ENDIAN);
    chunk.putInt(0x7fff7fff);
    chunk.put(frames(0, 4));
    chunk.put(new byte[3]);
    chunk.flip().position(4);
    final ByteBuffer faster = resampler.resample(chunk, DOUBLE_SPEED);
    final short[] leftSamples = left(faster);
    assertArrayEquals(new short[] { 0, 20 }, leftSamples);

    final int position = chunk.position();
    assertEquals(4, position);
  }
}
