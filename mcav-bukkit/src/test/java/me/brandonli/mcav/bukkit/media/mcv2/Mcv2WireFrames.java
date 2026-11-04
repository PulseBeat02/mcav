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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// hand-written wire fixtures reach valid syntax the optimizing writer never emits
final class Mcv2WireFrames {

  private static final int MAGIC_OFFSET = 0;
  private static final int MAGIC = 0x3256434D;
  private static final int VERSION_OFFSET = 4;
  private static final byte VERSION = 3;
  private static final int WIDTH_OFFSET = 8;
  private static final int HEIGHT_OFFSET = 10;
  private static final int FRAME_ID_OFFSET = 12;
  private static final int REFERENCE_ID_OFFSET = 16;
  private static final int HEADER_BYTES = 20;

  private static final long FRAME_ID = 1L;
  private static final long PREVIOUS_FRAME_ID = 0L;

  private static final int LEVEL_COUNT = 3;

  private static final int HALF_ROOT_SIZE = Mcv2Decoder.ROOT_SIZE / 2;

  private static final int CHECKPOINT_INTERVAL = Mcv2Decoder.CHECKPOINT_GROUPS * Mcv2Decoder.GROUP_ROOTS;

  private static final int MODE_MASK = 31;

  private Mcv2WireFrames() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static byte[] block(final int size, final int mode, final int quantizer, final byte[] record, final Mcv2FrameKind kind) {
    final byte value = (byte) (mode | (quantizer << Mcv2Decoder.QUANTIZER_SHIFT));
    final byte[] descriptors = switch (size) {
      case Mcv2Decoder.ROOT_SIZE -> new byte[] { value };
      case HALF_ROOT_SIZE -> new byte[] { Mcv2Decoder.MODE_SPLIT, value, 0, 0, 0 };
      default -> new byte[] { Mcv2Decoder.MODE_SPLIT, Mcv2Decoder.MODE_SPLIT, 0, 0, 0, value, 0, 0, 0 };
    };
    final int recordIndex = switch (size) {
      case Mcv2Decoder.ROOT_SIZE -> 0;
      case HALF_ROOT_SIZE -> 1;
      default -> 5;
    };
    final int[] levels = switch (size) {
      case Mcv2Decoder.ROOT_SIZE -> new int[] { 1, 0, 0 };
      case HALF_ROOT_SIZE -> new int[] { 1, 4, 0 };
      default -> new int[] { 1, 4, 4 };
    };
    return frame(size, size, kind, descriptors, records(descriptors.length, recordIndex, record), levels);
  }

  private static byte[][] records(final int count, final int index, final byte[] value) {
    final byte[][] records = new byte[count][0];
    records[index] = value;
    return records;
  }

  static byte[] frame(
    final int width,
    final int height,
    final Mcv2FrameKind kind,
    final byte[] descriptors,
    final byte[][] records,
    final int[] levels
  ) {
    final int roots = Math.ceilDiv(width, Mcv2Decoder.ROOT_SIZE) * Math.ceilDiv(height, Mcv2Decoder.ROOT_SIZE);
    final int groups = Math.ceilDiv(roots, Mcv2Decoder.GROUP_ROOTS);
    final int checkpoints = Math.ceilDiv(groups, Mcv2Decoder.CHECKPOINT_GROUPS);
    final int countsAt = HEADER_BYTES + Integer.BYTES * (groups + checkpoints);
    final int descriptorsAt = countsAt + Integer.BYTES * LEVEL_COUNT;
    final int walksAt = descriptorsAt + descriptors.length;
    final int start = walksAt + Integer.BYTES * Math.ceilDiv(descriptors.length, Mcv2Decoder.WALK_SPAN);
    int length = start;
    for (final byte[] record : records) {
      length += record.length;
    }

    final byte[] data = new byte[length];
    writeHeader(data, width, height, kind);
    for (int index = 0; index < levels[0]; index++) {
      data[HEADER_BYTES + index / Byte.SIZE] |= (byte) (1 << (index % Byte.SIZE));
    }

    writeCheckpoints(data, groups, checkpoints, levels[0]);

    for (int index = 0; index < LEVEL_COUNT; index++) {
      put(data, countsAt + Integer.BYTES * index, levels[index], Integer.BYTES);
    }

    writeRecords(data, descriptors, records, descriptorsAt, walksAt, start);

    return data;
  }

  private static void writeHeader(final byte[] data, final int width, final int height, final Mcv2FrameKind kind) {
    put(data, MAGIC_OFFSET, MAGIC, Integer.BYTES);
    data[VERSION_OFFSET] = VERSION;
    put(data, WIDTH_OFFSET, width, Short.BYTES);
    put(data, HEIGHT_OFFSET, height, Short.BYTES);
    put(data, FRAME_ID_OFFSET, FRAME_ID, Integer.BYTES);
    put(data, REFERENCE_ID_OFFSET, kind == Mcv2FrameKind.KEYFRAME ? FRAME_ID : PREVIOUS_FRAME_ID, Integer.BYTES);
  }

  private static void writeCheckpoints(final byte[] data, final int groups, final int checkpoints, final int roots) {
    for (int index = 0; index < checkpoints; index++) {
      put(data, HEADER_BYTES + groups * Integer.BYTES + index * Integer.BYTES, Math.min(roots, index * CHECKPOINT_INTERVAL), Integer.BYTES);
    }
  }

  private static void writeRecords(
    final byte[] data,
    final byte[] descriptors,
    final byte[][] records,
    final int descriptorsAt,
    final int walksAt,
    final int start
  ) {
    System.arraycopy(descriptors, 0, data, descriptorsAt, descriptors.length);
    int cursor = 0;
    int splits = 0;
    for (int index = 0; index < descriptors.length; index++) {
      if (index % Mcv2Decoder.WALK_SPAN == 0) {
        put(
          data,
          walksAt + (index / Mcv2Decoder.WALK_SPAN) * Integer.BYTES,
          cursor | ((long) splits << Mcv2Decoder.CURSOR_BITS),
          Integer.BYTES
        );
      }
      System.arraycopy(records[index], 0, data, start + cursor, records[index].length);
      cursor += records[index].length;
      if ((descriptors[index] & MODE_MASK) == Mcv2Decoder.MODE_SPLIT) {
        splits++;
      }
    }
  }

  static void put(final byte[] data, final int offset, final long value, final int bytes) {
    for (int index = 0; index < bytes; index++) {
      data[offset + index] = (byte) (value >>> (Byte.SIZE * index));
    }
  }

  static long readUnsignedInt(final byte[] data, final int offset) {
    final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    return Integer.toUnsignedLong(buffer.getInt(offset));
  }
}
