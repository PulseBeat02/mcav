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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link MapTilePatch}.
 */
final class MapTilePatchTest {

  @Test
  void storesTheRectangleAndItsColorsWithoutCopying() {
    final byte[] colors = { 1, 2, 3, 4, 5, 6 };
    final MapTilePatch patch = new MapTilePatch(7, 1, 2, 3, 2, colors);
    final int mapId = patch.getMapId();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] storedColors = patch.getColors();

    assertEquals(7, mapId);
    assertEquals(1, x);
    assertEquals(2, y);
    assertEquals(3, width);
    assertEquals(2, height);
    assertSame(colors, storedColors);
  }

  @Test
  void estimatesTheEncodedSizeFromTheColorsAndTheOverhead() {
    final byte[] colors = new byte[6];
    final MapTilePatch patch = new MapTilePatch(0, 0, 0, 3, 2, colors);
    final int size = patch.getEncodedSize();

    assertEquals(6 + MapLayout.PATCH_OVERHEAD, size);
  }

  @Test
  void acceptsPatchesThatTouchTheBorderOfTheMap() {
    final byte[] fullColors = new byte[128 * 128];
    final byte[] cornerColors = new byte[8 * 8];
    final MapTilePatch full = new MapTilePatch(0, 0, 0, 128, 128, fullColors);
    final MapTilePatch corner = new MapTilePatch(0, 120, 120, 8, 8, cornerColors);
    final int fullWidth = full.getWidth();
    final int cornerX = corner.getX();

    assertEquals(128, fullWidth);
    assertEquals(120, cornerX);
  }

  @Test
  void rejectsRectanglesOutsideOfTheMap() {
    final byte[] one = new byte[1];
    final byte[] eight = new byte[8];

    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, -1, 0, 1, 1, one));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, -1, 1, 1, one));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, 0, 0, 1, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, 0, 1, 0, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 121, 0, 8, 1, eight));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, 121, 1, 8, eight));
  }

  @Test
  void rejectsOriginsWhoseRectangleEndsOverflow() {
    final byte[] two = new byte[2];

    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, Integer.MAX_VALUE, 0, 2, 1, two));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, Integer.MAX_VALUE, 1, 2, two));
  }

  @Test
  void rejectsInvalidIdsAndColors() {
    final byte[] one = new byte[1];
    final byte[] two = new byte[2];

    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(-1, 0, 0, 1, 1, one));
    assertThrows(IllegalArgumentException.class, () -> new MapTilePatch(0, 0, 0, 1, 1, two));
    assertThrows(NullPointerException.class, () -> new MapTilePatch(0, 0, 0, 1, 1, null));
  }
}
