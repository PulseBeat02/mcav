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

final class Mcv2TreeNodeTest {

  private static final Node SOLID = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 1, 2, 3 });

  @Test
  void shouldMakeSkipASharedEmptyLeaf() {
    final Node skip = Node.skip();

    final Object expectedValue = skip.value();
    final Object sharedValue = Node.skip().value();
    assertSame(expectedValue, sharedValue);

    final int mode = skip.getMode();
    assertEquals(Mcv2Decoder.MODE_SKIP, mode);

    final int quantizer = skip.getQuantizer();
    assertEquals(0, quantizer);

    final boolean split = skip.isSplit();
    assertFalse(split);

    final int recordLength = skip.getRecord().length;
    assertEquals(0, recordLength);
  }

  @Test
  void shouldRetainTheRecordsOfEncodedFramesWhenTheCoderIsReused() {
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
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

    final byte[] decoded = Mcv2Decoder.decode(Mcv2Decoder.parse(data), null, 0);
    assertArrayEquals(expected, decoded);
  }

  @Test
  void shouldHoldFourChildrenInRasterOrderInASplit() {
    final Node bottomLeft = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 4, 5, 6 });
    final Node bottomRight = Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 7, 8, 9 });
    final Node[] children = { SOLID, Node.skip(), bottomLeft, bottomRight };
    final Node split = Node.split(children[0], children[1], children[2], children[3]);

    final boolean isSplit = split.isSplit();
    assertTrue(isSplit);

    final int mode = split.getMode();
    assertEquals(Mcv2Decoder.MODE_SPLIT, mode);

    for (int index = 0; index < children.length; index++) {
      final Node child = split.getChild(index);
      assertSame(children[index].value(), child.value());
    }
  }
}
