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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A viewer's link: what it can decode, and frames held back while its connection is behind. */
final class Mcv2LinkTest {

  @Test
  void startsWithAKeyframe() {
    final Mcv2Link link = new Mcv2Link(1000);
    // a viewer that has decoded nothing can only start at a keyframe
    assertFalse(link.canDecode(false, 0));
    // the id no frame has is never a reference, not even before the first frame
    assertFalse(link.canDecode(false, -1));
    assertFalse(link.offer(5, 4, false, 10));
    assertEquals(1, link.getUndecodable());
    assertTrue(link.offer(6, 6, true, 10));
    assertTrue(link.offer(7, 6, false, 10));
    assertEquals(2, link.getSent());
    assertEquals(20, link.getBacklog());
  }

  @Test
  void waitsForTheNextKeyframeWhenFramesPredictFromTheFrameBefore() {
    final Mcv2Link link = new Mcv2Link(100);
    assertTrue(link.offer(0, 0, true, 60));
    assertTrue(link.offer(1, 0, false, 60));
    // 120 bytes not yet written: frame 2 is held back, and 3 predicts from 2, which the viewer lacks
    assertFalse(link.offer(2, 1, false, 60));
    link.written(120);
    assertFalse(link.offer(3, 2, false, 60));
    assertFalse(link.offer(4, 3, false, 60));
    assertTrue(link.offer(5, 5, true, 60));
    assertTrue(link.offer(6, 5, false, 60));
    assertEquals(1, link.getBehind());
    assertEquals(2, link.getUndecodable());
    assertEquals(4, link.getSent());
  }

  @Test
  void resumesAtTheNextFrameWhenFramesPredictFromTheKeyframe() {
    final Mcv2Link link = new Mcv2Link(100);
    assertTrue(link.offer(10, 10, true, 90));
    assertTrue(link.offer(11, 10, false, 90));
    assertFalse(link.offer(12, 10, false, 90));
    link.written(180);
    // the keyframe is still held, so the next frame decodes
    assertTrue(link.offer(13, 10, false, 90));
    assertEquals(1, link.getBehind());
    assertEquals(0, link.getUndecodable());
  }

  @Test
  void sendsAtTheLimitAndHoldsPastIt() {
    final Mcv2Link link = new Mcv2Link(50);
    assertTrue(link.offer(0, 0, true, 50));
    // exactly at the limit is not behind
    assertTrue(link.offer(1, 0, false, 1));
    assertFalse(link.offer(2, 1, false, 1));
    final Mcv2Link none = new Mcv2Link(0);
    assertTrue(none.offer(0, 0, true, 5));
    assertFalse(none.offer(1, 0, false, 5));
  }

  @Test
  void refusesValuesOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Link(-1));
    final Mcv2Link link = new Mcv2Link(10);
    assertThrows(IllegalArgumentException.class, () -> link.offer(-1, 0, true, 1));
    assertThrows(IllegalArgumentException.class, () -> link.offer(1L << 32, 0, true, 1));
    assertThrows(IllegalArgumentException.class, () -> link.offer(0, 0, true, -1));
    // the largest frame id is a frame id
    assertTrue(link.offer(0xFFFFFFFFL, 0xFFFFFFFFL, true, 1));
    assertTrue(link.canDecode(false, 0xFFFFFFFFL));
  }
}
