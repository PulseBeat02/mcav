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
package me.brandonli.mcav.bukkit.media.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link MapRegion}.
 */
final class MapRegionTest {

  @Test
  void storesBothCoordinateSystems() {
    final MapRegion region = new MapRegion(1, 2, 3, 4, 5, 6);
    final int localX = region.getLocalX();
    final int localY = region.getLocalY();
    final int width = region.getWidth();
    final int height = region.getHeight();
    final int sourceX = region.getSourceX();
    final int sourceY = region.getSourceY();
    final boolean empty = region.isEmpty();

    assertEquals(1, localX);
    assertEquals(2, localY);
    assertEquals(3, width);
    assertEquals(4, height);
    assertEquals(5, sourceX);
    assertEquals(6, sourceY);
    assertFalse(empty);
  }

  @Test
  void isEmptyWithoutWidthOrHeight() {
    final MapRegion noWidth = new MapRegion(0, 0, 0, 4, 0, 0);
    final MapRegion noHeight = new MapRegion(0, 0, 4, 0, 0, 0);
    final MapRegion negative = new MapRegion(0, 0, -1, -1, 0, 0);
    final boolean noWidthEmpty = noWidth.isEmpty();
    final boolean noHeightEmpty = noHeight.isEmpty();
    final boolean negativeEmpty = negative.isEmpty();

    assertTrue(noWidthEmpty);
    assertTrue(noHeightEmpty);
    assertTrue(negativeEmpty);
  }
}
