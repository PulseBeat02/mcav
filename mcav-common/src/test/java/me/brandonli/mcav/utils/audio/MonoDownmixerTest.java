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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MonoDownmixer}.
 */
final class MonoDownmixerTest {

  private static ByteBuffer stereo(final short... leftAndRight) {
    final ByteBuffer buffer = ByteBuffer.allocate(leftAndRight.length * Short.BYTES);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    for (final short sample : leftAndRight) {
      buffer.putShort(sample);
    }
    buffer.flip();
    return buffer;
  }

  @Test
  void averagesTheLeftAndRightChannels() {
    final ByteBuffer samples = stereo((short) 1000, (short) 3000, (short) -200, (short) 200, (short) 7, (short) 8, (short) -7, (short) -8);
    final short[] mono = MonoDownmixer.downmix(samples);
    final short[] expected = { 2000, 0, 7, -8 };
    assertArrayEquals(expected, mono);
  }

  @Test
  void roundsHalfSamplesTowardNegativeInfinity() {
    final ByteBuffer samples = stereo((short) -1, (short) 0, (short) 1, (short) 0, (short) -3, (short) 0);
    final short[] mono = MonoDownmixer.downmix(samples);
    final short[] expected = { -1, 0, -2 };
    assertArrayEquals(expected, mono);
  }

  @Test
  void doesNotOverflowAtTheExtremes() {
    final ByteBuffer samples = stereo(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE);
    final short[] mono = MonoDownmixer.downmix(samples);
    final short[] expected = { Short.MAX_VALUE, Short.MIN_VALUE, -1 };
    assertArrayEquals(expected, mono);
  }

  @Test
  void readsLittleEndianRegardlessOfTheBufferOrderAndLeavesTheBufferUnchanged() {
    final byte[] bytes = { 0x10, 0x00, 0x30, 0x00, 0x00, 0x01, 0x00, 0x03 };
    final ByteBuffer samples = ByteBuffer.wrap(bytes);
    samples.order(ByteOrder.BIG_ENDIAN);
    final short[] mono = MonoDownmixer.downmix(samples);
    final short[] expected = { 0x20, 0x200 };
    final int position = samples.position();
    final ByteOrder order = samples.order();
    assertArrayEquals(expected, mono);
    assertEquals(0, position);
    assertEquals(ByteOrder.BIG_ENDIAN, order);
  }

  @Test
  void readsFromThePositionAndIgnoresAnIncompleteTrailingSample() {
    final ByteBuffer samples = ByteBuffer.allocate(4 + 4 + 3);
    samples.order(ByteOrder.LITTLE_ENDIAN);
    samples.putShort((short) 111);
    samples.putShort((short) 111);
    samples.putShort((short) 40);
    samples.putShort((short) 60);
    samples.put((byte) 1);
    samples.put((byte) 2);
    samples.put((byte) 3);
    samples.flip();
    samples.position(4);
    final short[] mono = MonoDownmixer.downmix(samples);
    final short[] expected = { 50 };
    assertArrayEquals(expected, mono);
  }

  @Test
  void returnsNoSamplesForAnEmptyBuffer() {
    final ByteBuffer empty = ByteBuffer.allocate(0);
    final short[] mono = MonoDownmixer.downmix(empty);
    assertEquals(0, mono.length);
  }

  @Test
  void rejectsNull() {
    assertThrows(NullPointerException.class, () -> MonoDownmixer.downmix(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MonoDownmixer.class);
  }
}
