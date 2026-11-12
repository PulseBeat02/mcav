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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.junit.jupiter.api.Test;

final class VolumeFilterTest {

  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of("", 0, AudioFilter.SAMPLE_RATE, AudioFilter.CHANNELS, 0);

  private static ByteBuffer samples(final short... values) {
    final ByteBuffer buffer = ByteBuffer.allocate(values.length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (final short value : values) {
      buffer.putShort(value);
    }

    return buffer.flip();
  }

  private static short[] read(final ByteBuffer buffer) {
    final ByteBuffer view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    final short[] values = new short[view.remaining() / Short.BYTES];
    for (int sampleIndex = 0; sampleIndex < values.length; sampleIndex++) {
      values[sampleIndex] = view.getShort();
    }

    return values;
  }

  @Test
  void shouldLeaveTheAudioAsItIsUntilTheVolumeChanges() {
    final VolumeFilter filter = new VolumeFilter();
    final double volume = filter.getVolume();
    assertEquals(VolumeFilter.UNCHANGED, volume);

    final ByteBuffer buffer = samples((short) 100, (short) -7);
    final boolean changed = filter.applyFilter(buffer, METADATA);
    assertFalse(changed);

    final short[] values = read(buffer);
    final short[] expected = new short[] { 100, -7 };
    assertArrayEquals(expected, values);
  }

  @Test
  void shouldScaleEverySampleAndRound() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(0.5);
    final ByteBuffer buffer = samples((short) 100, (short) -7, (short) 3, (short) 0);
    final boolean halved = filter.applyFilter(buffer, METADATA);
    assertTrue(halved);

    final short[] halvedValues = read(buffer);
    final short[] expectedHalvedValues = new short[] { 50, -3, 2, 0 };
    assertArrayEquals(expectedHalvedValues, halvedValues);

    final int position = buffer.position();
    assertEquals(0, position);

    filter.setVolume(0);
    final boolean silenced = filter.applyFilter(buffer, METADATA);
    assertTrue(silenced);

    final short[] silencedValues = read(buffer);
    final short[] expectedSilencedValues = new short[] { 0, 0, 0, 0 };
    assertArrayEquals(expectedSilencedValues, silencedValues);
  }

  @Test
  void shouldClipLoudSamplesToTheirRange() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(VolumeFilter.MAXIMUM_VOLUME);
    final ByteBuffer buffer = samples(Short.MAX_VALUE, Short.MIN_VALUE, (short) 20_000, (short) -16_384);
    filter.applyFilter(buffer, METADATA);
    final short[] values = read(buffer);
    final short[] expected = new short[] { Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE };
    assertArrayEquals(expected, values);
  }

  @Test
  void shouldThrowOnlyWhenScalingWritesToAReadOnlyBuffer() {
    final VolumeFilter filter = new VolumeFilter();
    final ByteBuffer writable = samples((short) 100);
    final ByteBuffer buffer = writable.asReadOnlyBuffer();
    final boolean changed = filter.applyFilter(buffer, METADATA);
    assertFalse(changed);

    filter.setVolume(0.5);
    assertThrows(ReadOnlyBufferException.class, () -> filter.applyFilter(buffer, METADATA));
  }

  @Test
  void shouldScaleOnlyFromThePositionToTheLimitAndLeaveAnOddByte() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(2);
    final ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
    buffer
      .putShort(0, (short) 5)
      .putShort(2, (short) 6)
      .putShort(4, (short) 7)
      .put(6, (byte) 9);
    buffer.position(2);
    filter.applyFilter(buffer, METADATA);
    final short beforePosition = buffer.getShort(0);
    assertEquals(5, beforePosition);

    final short firstScaled = buffer.getShort(2);
    assertEquals(12, firstScaled);

    final short secondScaled = buffer.getShort(4);
    assertEquals(14, secondScaled);

    final byte oddByte = buffer.get(6);
    assertEquals(9, oddByte);

    final int position = buffer.position();
    assertEquals(2, position);
  }

  @Test
  void shouldReadLittleEndianSamplesUpToTheLimitWithoutChangingTheLimitOrTheByteOrder() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(VolumeFilter.MAXIMUM_VOLUME);
    final ByteBuffer buffer = ByteBuffer.allocate(6);
    final ByteBuffer littleEndian = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    littleEndian
      .putShort(0, (short) 128)
      .putShort(2, (short) 300)
      .putShort(4, (short) 7);
    buffer.limit(4);
    filter.applyFilter(buffer, METADATA);
    final short[] values = read(buffer);
    final short[] expected = new short[] { 256, 600 };
    assertArrayEquals(expected, values);

    final short afterLimit = littleEndian.getShort(4);
    assertEquals(7, afterLimit);

    final int limit = buffer.limit();
    final ByteOrder order = buffer.order();
    assertEquals(4, limit);
    assertEquals(ByteOrder.BIG_ENDIAN, order);
  }

  @Test
  void shouldRefuseAVolumeOutsideItsRange() {
    final VolumeFilter filter = new VolumeFilter();
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(-0.01));
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(VolumeFilter.MAXIMUM_VOLUME + 0.01));
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(Double.NaN));

    filter.setVolume(VolumeFilter.MAXIMUM_VOLUME);
    final double volume = filter.getVolume();
    assertEquals(VolumeFilter.MAXIMUM_VOLUME, volume);
  }

  @Test
  void shouldRejectMissingSamples() {
    final VolumeFilter filter = new VolumeFilter();
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, METADATA));
  }
}
