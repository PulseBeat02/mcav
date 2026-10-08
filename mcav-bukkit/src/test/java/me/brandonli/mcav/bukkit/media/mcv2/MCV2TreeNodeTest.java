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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

/** Private tree structure and ownership of encoded records. */
final class MCV2TreeNodeTest {

  private static final Node SOLID = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 1, 2, 3 });

  @Test
  void skipIsASharedEmptyLeaf() {
    final Node skip = Node.skip();
    assertSame(skip.value(), Node.skip().value());
    assertEquals(Mcv2Decoder.MODE_SKIP, skip.getMode());
    assertEquals(0, skip.getQuantizer());
    assertFalse(skip.isSplit());
    assertEquals(0, skip.getRecord().length);
  }

  @Test
  void encodedFramesRetainTheirRecordsWhenTheCoderIsReused() throws Mcv2Exception {
    final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
    final byte[] source = new byte[64 * 32 * 3];
    for (int pixel = 0; pixel < 64 * 32; pixel++) {
      Arrays.fill(source, pixel * 3, pixel * 3 + 3, (byte) (pixel % 64 < 32 ? 17 : 41));
    }
    final byte[] expected = source.clone();
    final byte[] data = encoder.encode(source, 64, 32, 0);
    final byte[] saved = data.clone();
    Arrays.fill(source, (byte) 93);
    encoder.requestKeyframe();
    encoder.encode(source, 64, 32, 1);
    assertArrayEquals(saved, data);
    assertArrayEquals(expected, Mcv2Decoder.decode(Mcv2Decoder.parse(data), null, 0));
  }

  @Test
  void splitsHoldFourChildrenInRasterOrder() {
    final Node split = Node.split(SOLID, Node.skip(), Node.skip(), SOLID);
    assertTrue(split.isSplit());
    assertEquals(Mcv2Decoder.MODE_SPLIT, split.getMode());
    assertSame(SOLID.value(), split.getChild(0).value());
    assertSame(SOLID.value(), split.getChild(3).value());
  }
}
