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
  void leavesTheAudioAsItIsUntilTheVolumeChanges() {
    final VolumeFilter filter = new VolumeFilter();
    assertEquals(VolumeFilter.UNCHANGED, filter.getVolume());
    final ByteBuffer buffer = samples((short) 100, (short) -7);
    assertFalse(filter.applyFilter(buffer, METADATA));
    assertArrayEquals(new short[] { 100, -7 }, read(buffer));
  }

  @Test
  void scalesEverySampleAndRounds() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(0.5);
    final ByteBuffer buffer = samples((short) 100, (short) -7, (short) 3, (short) 0);
    assertTrue(filter.applyFilter(buffer, METADATA));
    // -3.5 and 1.5 round half up
    assertArrayEquals(new short[] { 50, -3, 2, 0 }, read(buffer));
    assertEquals(0, buffer.position());
    filter.setVolume(0);
    assertTrue(filter.applyFilter(buffer, METADATA));
    assertArrayEquals(new short[] { 0, 0, 0, 0 }, read(buffer));
  }

  @Test
  void clipsLoudSamplesToTheirRange() {
    final VolumeFilter filter = new VolumeFilter();
    filter.setVolume(VolumeFilter.MAX_VOLUME);
    final ByteBuffer buffer = samples(Short.MAX_VALUE, Short.MIN_VALUE, (short) 20_000, (short) -16_384);
    filter.applyFilter(buffer, METADATA);
    assertArrayEquals(new short[] { Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE }, read(buffer));
  }

  @Test
  void scalesOnlyFromThePositionToTheLimitAndLeavesAnOddByte() {
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
    assertEquals(5, buffer.getShort(0));
    assertEquals(12, buffer.getShort(2));
    assertEquals(14, buffer.getShort(4));
    assertEquals(9, buffer.get(6));
    assertEquals(2, buffer.position());
  }

  @Test
  void refusesAVolumeOutsideItsRange() {
    final VolumeFilter filter = new VolumeFilter();
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(-0.01));
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(VolumeFilter.MAX_VOLUME + 0.01));
    assertThrows(IllegalArgumentException.class, () -> filter.setVolume(Double.NaN));
    filter.setVolume(VolumeFilter.MAX_VOLUME);
    assertEquals(VolumeFilter.MAX_VOLUME, filter.getVolume());
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, METADATA));
  }
}
