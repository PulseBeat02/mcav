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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class Mcv2CompactTest {

  private static final int LARGEST_BLOCK_SIZE = 32;

  private static final int RECORD_LENGTH = 10;

  @Test
  void shouldStoreBothWholePixelVectorsInEveryTenByteRecord() {
    for (final int motionX : new int[] { -128, -7, 0, 7, 127 }) {
      for (final int motionY : new int[] { -128, -1, 0, 7, 127 }) {
        final byte[] record = { (byte) motionX, (byte) motionY, -128, 127, -1, 0, 0x18, 0x72, 0x4E, 0x5A };
        final byte[] data = Mcv2WireFrames.block(LARGEST_BLOCK_SIZE, 5, 2, record, Mcv2FrameKind.PREDICTED);
        final int offset = Mcv2Decoder.parse(data).getLeaf(0).offset();
        final byte storedX = data[offset];
        assertEquals(motionX, storedX);
        final byte storedY = data[offset + 1];
        assertEquals(motionY, storedY);
        final int recordLength = data.length - offset;
        assertEquals(RECORD_LENGTH, recordLength);
        final byte[] storedRecord = Arrays.copyOfRange(data, offset, data.length);
        assertArrayEquals(record, storedRecord);
      }
    }
  }

  @Test
  void shouldRequireExactlyTenBytesForEveryBlockSize() {
    for (final int size : new int[] { 8, 16, LARGEST_BLOCK_SIZE }) {
      for (int length = 0; length <= 20; length++) {
        this.assertRecordLengthChecked(size, length);
      }
    }
  }

  private void assertRecordLengthChecked(final int size, final int length) {
    final byte[] data = Mcv2WireFrames.block(size, 5, 0, new byte[length], Mcv2FrameKind.PREDICTED);
    if (length != RECORD_LENGTH) {
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
      return;
    }

    final int payloadLength = data.length - Mcv2Decoder.parse(data).getPayloadStart();
    assertEquals(RECORD_LENGTH, payloadLength);
  }

  @Test
  void shouldRefuseEveryQuantizerAboveTwo() {
    for (int quantizer = 3; quantizer < 8; quantizer++) {
      final byte[] data = Mcv2WireFrames.block(LARGEST_BLOCK_SIZE, 5, quantizer, new byte[RECORD_LENGTH], Mcv2FrameKind.PREDICTED);
      assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(data));
    }
  }

  @Test
  void shouldDecodeTheWorkedCompactExampleToItsStatedPixel() {
    final int size = 16;
    final byte[] color = { 100, 120, (byte) 140 };
    final byte[] reference = new byte[size * size * Mcv2Decoder.CHANNELS];
    for (int index = 0; index < reference.length; index += Mcv2Decoder.CHANNELS) {
      System.arraycopy(color, 0, reference, index, Mcv2Decoder.CHANNELS);
    }

    final byte leftNodes = (byte) 0xFE;
    final byte rightNodes = 0x10;
    final byte[] record = { 0, 0, leftNodes, rightNodes, leftNodes, rightNodes, leftNodes, rightNodes, leftNodes, rightNodes };
    final byte[] data = Mcv2WireFrames.block(size, Mcv2Decoder.MODE_COMPACT, 2, record, Mcv2FrameKind.PREDICTED);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    final byte[] picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
    final int column = 2;
    final byte[] expected = { 93, 113, (byte) 133 };
    final byte[] top = this.pixelAt(picture, size, column, 0);
    assertArrayEquals(expected, top);

    final byte[] bottom = this.pixelAt(picture, size, column, size - 1);
    assertArrayEquals(expected, bottom);
  }

  private byte[] pixelAt(final byte[] picture, final int width, final int column, final int row) {
    final int offset = (row * width + column) * Mcv2Decoder.CHANNELS;
    return Arrays.copyOfRange(picture, offset, offset + Mcv2Decoder.CHANNELS);
  }
}
