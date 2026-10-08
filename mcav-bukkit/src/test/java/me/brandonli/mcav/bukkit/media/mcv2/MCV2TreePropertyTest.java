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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

final class MCV2TreePropertyTest {

  @Property(seed = "20261003", tries = 1000)
  void randomTreesPreserveEveryLeafAndWalkCheckpoint(@ForAll final long seed) throws Mcv2Exception {
    final Random random = new Random(seed);
    final int width = 1 + random.nextInt(100);
    final int height = 1 + random.nextInt(100);
    final List<Node> roots = new ArrayList<>();
    for (int root = 0; root < ((width + 31) / 32) * ((height + 31) / 32); root++) {
      roots.add(tree(random, 32));
    }
    final byte[] data = Mcv2Trees.write(width, height, 2, 1, false, roots);
    final Mcv2Decoder.Frame parsed = Mcv2Decoder.parse(data);
    assertEquals(roots, Mcv2Trees.read(parsed));
    assertArrayEquals(data, Mcv2Trees.write(width, height, 2, 1, false, Mcv2Trees.read(parsed)));
    final int groups = (roots.size() + 31) / 32;
    final int counts = 20 + 4 * (groups + (groups + 7) / 8);
    final int descriptors = (int) (Mcv2Decoder.u32(data, counts) + Mcv2Decoder.u32(data, counts + 4) + Mcv2Decoder.u32(data, counts + 8));
    final int walk = counts + 12 + descriptors;
    int splits = 0;
    int cursor = 0;
    int leaf = 0;
    for (int descriptor = 0; descriptor < descriptors; descriptor++) {
      if (descriptor % 8 == 0) {
        assertEquals(cursor | ((long) splits << 17), Mcv2Decoder.u32(data, walk + (descriptor / 8) * 4));
        final byte[] corrupt = data.clone();
        corrupt[walk + (descriptor / 8) * 4] ^= 1;
        assertThrows(Mcv2Exception.class, () -> Mcv2Decoder.parse(corrupt));
      }
      final int mode = data[counts + 12 + descriptor] & 31;
      if (mode == 6) {
        splits++;
      } else {
        final Mcv2Decoder.Leaf value = parsed.getLeaf(leaf++);
        assertEquals(parsed.getPayloadStart() + cursor, value.offset());
        cursor += length(data, value);
      }
    }
  }

  private static int length(final byte[] data, final Mcv2Decoder.Leaf leaf) {
    if (leaf.mode() == 5) {
      final int control = data[leaf.offset()] & 255;
      return (
        1 +
        (control >> 4) +
        switch (control & 15) {
          case 0 -> 1;
          case 1 -> 10;
          default -> 8;
        }
      );
    }
    return Mcv2Decoder.recordSize(leaf.mode(), leaf.size());
  }

  static Node tree(final Random random, final int size) {
    if (size > 8 && random.nextBoolean()) {
      return Node.split(tree(random, size / 2), tree(random, size / 2), tree(random, size / 2), tree(random, size / 2));
    }
    final int mode = random.nextInt(6);
    final int quantizer = mode == 5 ? random.nextInt(8) : 0;
    final byte[] record;
    if (mode == 5) {
      final int kind = random.nextInt(3);
      final int form = random.nextInt(3);
      record = new byte[1 + form + (kind == 0 ? 1 : kind == 1 ? 10 : 8)];
      random.nextBytes(record);
      record[0] = (byte) (kind | (form << 4));
    } else if (mode == 4) {
      record = new byte[7 + size / 8];
      random.nextBytes(record);
      record[6] &= 1;
      if (random.nextBoolean()) {
        System.arraycopy(new byte[] { 0, 0, 0, -1, -1, -1 }, 0, record, 0, 6);
      }
    } else {
      record = new byte[Mcv2Decoder.recordSize(mode, size)];
      random.nextBytes(record);
    }
    return Node.leaf(mode, quantizer, record);
  }
}
