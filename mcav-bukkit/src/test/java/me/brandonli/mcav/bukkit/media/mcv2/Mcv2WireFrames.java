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

/** Independent wire fixtures can express valid syntax the optimizing writer never emits. */
final class Mcv2WireFrames {

  private Mcv2WireFrames() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static byte[] block(final int size, final int mode, final int quantizer, final byte[] record, final boolean keyframe) {
    final byte value = (byte) (mode | (quantizer << 5));
    final byte[] descriptors =
      size == 32 ? new byte[] { value } : size == 16 ? new byte[] { 6, value, 0, 0, 0 } : new byte[] { 6, 6, 0, 0, 0, value, 0, 0, 0 };
    return frame(
      size,
      size,
      keyframe,
      descriptors,
      records(descriptors.length, size == 32 ? 0 : size == 16 ? 1 : 5, record),
      size == 32 ? new int[] { 1, 0, 0 } : size == 16 ? new int[] { 1, 4, 0 } : new int[] { 1, 4, 4 },
      new byte[0],
      new byte[][] { {}, {}, {} }
    );
  }

  static byte[][] records(final int count, final int index, final byte[] value) {
    final byte[][] records = new byte[count][0];
    records[index] = value;
    return records;
  }

  static byte[] frame(
    final int width,
    final int height,
    final boolean keyframe,
    final byte[] descriptors,
    final byte[][] records,
    final int[] levels,
    final byte[] endpoints,
    final byte[][] words
  ) {
    final int roots = ((width + 31) / 32) * ((height + 31) / 32);
    final int groups = (roots + 31) / 32;
    final int checkpoints = (groups + 7) / 8;
    final int countsAt = 32 + 4 * (groups + checkpoints);
    final int walksAt = countsAt + 12 + descriptors.length;
    final int start = walksAt + 4 * ((descriptors.length + 7) / 8) + 4;
    int length = start + endpoints.length;
    for (final byte[] record : records) {
      length += record.length;
    }
    for (final byte[] table : words) {
      length += table.length;
    }
    final byte[] data = new byte[length];
    put(data, 0, 0x3256434D, 4);
    data[4] = 3;
    data[5] = (byte) (keyframe ? 1 : 0);
    put(data, 8, width, 2);
    put(data, 10, height, 2);
    put(data, 12, 1, 4);
    put(data, 16, keyframe ? 1 : 0, 4);
    put(data, 20, start, 4);
    put(data, 24, length, 4);
    for (int index = 0; index < levels[0]; index++) {
      data[32 + index / 8] |= (byte) (1 << (index % 8));
    }
    for (int index = 0; index < checkpoints; index++) {
      put(data, 32 + groups * 4 + index * 4, Math.min(levels[0], index * 256), 4);
    }
    for (int index = 0; index < 3; index++) {
      put(data, countsAt + 4 * index, levels[index], 4);
    }
    System.arraycopy(descriptors, 0, data, countsAt + 12, descriptors.length);
    int cursor = 0;
    int splits = 0;
    for (int index = 0; index < descriptors.length; index++) {
      if (index % 8 == 0) {
        put(data, walksAt + (index / 8) * 4, cursor | ((long) splits << 17), 4);
      }
      System.arraycopy(records[index], 0, data, start + cursor, records[index].length);
      cursor += records[index].length;
      if ((descriptors[index] & 31) == 6) {
        splits++;
      }
    }
    data[start - 4] = (byte) (endpoints.length / 4);
    int tableAt = start + cursor;
    for (int index = 0; index < 3; index++) {
      data[start - 3 + index] = (byte) (words[index].length / (1 + (1 << index)));
      System.arraycopy(words[index], 0, data, tableAt, words[index].length);
      tableAt += words[index].length;
    }
    System.arraycopy(endpoints, 0, data, tableAt, endpoints.length);
    return data;
  }

  static void put(final byte[] data, final int at, final long value, final int bytes) {
    for (int index = 0; index < bytes; index++) {
      data[at + index] = (byte) (value >>> (8 * index));
    }
  }

  static long read(final byte[] data, final int at) {
    return (data[at] & 255L) | ((data[at + 1] & 255L) << 8) | ((data[at + 2] & 255L) << 16) | ((data[at + 3] & 255L) << 24);
  }
}
