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
package me.brandonli.mcav.utils.natives;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ByteUtils}.
 */
final class ByteUtilsTest {

  private static byte[] remainingBytes(final ByteBuffer buffer) {
    final ByteBuffer view = buffer.duplicate();
    final int remaining = view.remaining();
    final byte[] bytes = new byte[remaining];
    view.get(bytes);
    return bytes;
  }

  @Test
  void reportsTheNativeByteOrder() {
    final ByteOrder nativeOrder = ByteOrder.nativeOrder();
    final boolean expectedLittle = nativeOrder == ByteOrder.LITTLE_ENDIAN;
    final boolean little = ByteUtils.isLittleEndian();
    final boolean big = ByteUtils.isBigEndian();
    assertEquals(expectedLittle, little);
    assertNotEquals(little, big);
  }

  @Test
  void convertsShortSamplesToLittleEndianBytes() {
    final ShortBuffer samples = ShortBuffer.wrap(new short[] { 0x0102, 0x0304 });
    final ByteBuffer converted = ByteUtils.toLittleEndian(samples);
    final ByteOrder order = converted.order();
    final byte[] bytes = remainingBytes(converted);
    final int position = samples.position();
    assertEquals(ByteOrder.LITTLE_ENDIAN, order);
    assertArrayEquals(new byte[] { 0x02, 0x01, 0x04, 0x03 }, bytes);
    assertEquals(0, position, "the source must not be consumed");
  }

  @Test
  void convertsFloatSamplesToLittleEndianBytes() {
    final FloatBuffer samples = FloatBuffer.wrap(new float[] { 1.0f });
    final ByteBuffer converted = ByteUtils.toLittleEndian(samples);
    final int bits = Float.floatToIntBits(1.0f);
    final int remaining = converted.remaining();
    final int stored = converted.getInt(0);
    final ByteOrder order = converted.order();
    final byte[] bytes = remainingBytes(converted);
    assertEquals(Float.BYTES, remaining);
    assertEquals(bits, stored);
    assertEquals(ByteOrder.LITTLE_ENDIAN, order);
    assertArrayEquals(new byte[] { 0x00, 0x00, (byte) 0x80, 0x3F }, bytes, "1.0f is 0x3F800000, lowest byte first");
  }

  @Test
  void swapsBigEndianBytesToLittleEndian() {
    final ByteBuffer bigEndian = ByteBuffer.wrap(new byte[] { 0x01, 0x02, 0x03 });
    bigEndian.order(ByteOrder.BIG_ENDIAN);
    final ByteBuffer converted = ByteUtils.toLittleEndian(bigEndian);
    final byte[] bytes = remainingBytes(converted);
    assertArrayEquals(new byte[] { 0x02, 0x01, 0x03 }, bytes, "pairs are swapped and a trailing odd byte is kept");
  }

  @Test
  void copiesBytesThatAlreadyHaveTheTargetOrder() {
    final ByteBuffer littleEndian = ByteBuffer.wrap(new byte[] { 0x01, 0x02 });
    littleEndian.order(ByteOrder.LITTLE_ENDIAN);
    final ByteBuffer converted = ByteUtils.toLittleEndian(littleEndian);
    final byte[] bytes = remainingBytes(converted);
    assertArrayEquals(new byte[] { 0x01, 0x02 }, bytes);
  }

  @Test
  void swapsLittleEndianBytesToBigEndian() {
    final ByteBuffer littleEndian = ByteBuffer.wrap(new byte[] { 0x02, 0x01 });
    littleEndian.order(ByteOrder.LITTLE_ENDIAN);
    final ByteBuffer converted = ByteUtils.toBigEndian(littleEndian);
    final ByteOrder order = converted.order();
    final short sample = converted.getShort(0);
    final int position = littleEndian.position();
    assertEquals(ByteOrder.BIG_ENDIAN, order);
    assertEquals(0x0102, sample);
    assertEquals(0, position, "the source must not be consumed");
  }

  @Test
  void rejectsUnsupportedBuffers() {
    final IntBuffer integers = IntBuffer.allocate(1);
    assertThrows(PlayerException.class, () -> ByteUtils.toLittleEndian(integers));
    assertThrows(NullPointerException.class, () -> ByteUtils.toLittleEndian(null));
    assertThrows(NullPointerException.class, () -> ByteUtils.toBigEndian(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ByteUtils.class);
  }

  @Test
  void readsFromThePositionToTheLimit() {
    final ByteBuffer bigEndian = ByteBuffer.wrap(new byte[] { 0x09, 0x01, 0x02, 0x03, 0x04, 0x09 });
    bigEndian.order(ByteOrder.BIG_ENDIAN);
    bigEndian.position(1);
    bigEndian.limit(5);
    final ByteBuffer converted = ByteUtils.toLittleEndian(bigEndian);
    final byte[] bytes = remainingBytes(converted);
    final int position = bigEndian.position();
    final int limit = bigEndian.limit();
    assertArrayEquals(new byte[] { 0x02, 0x01, 0x04, 0x03 }, bytes);
    assertEquals(1, position);
    assertEquals(5, limit);
  }
}
