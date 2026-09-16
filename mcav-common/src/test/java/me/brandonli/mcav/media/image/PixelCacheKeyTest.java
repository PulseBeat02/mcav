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
package me.brandonli.mcav.media.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link PixelCacheKey}.
 */
final class PixelCacheKeyTest {

  @Test
  void followsTheEqualityContract() {
    final PixelCacheKey key = new PixelCacheKey(0x1000L, 4, 2);
    final PixelCacheKey equalKey = new PixelCacheKey(0x1000L, 4, 2);
    final PixelCacheKey otherAddress = new PixelCacheKey(0x2000L, 4, 2);
    final PixelCacheKey otherWidth = new PixelCacheKey(0x1000L, 2, 2);
    final PixelCacheKey otherHeight = new PixelCacheKey(0x1000L, 4, 1);
    EqualityAssertions.assertEqualityContract(key, equalKey, otherAddress, otherWidth, otherHeight);
  }

  @Test
  void exposesTheShape() {
    final PixelCacheKey key = new PixelCacheKey(0xABCL, 16, 9);
    final int width = key.getWidth();
    final int height = key.getHeight();
    final String text = key.toString();
    assertEquals(16, width);
    assertEquals(9, height);
    assertEquals("PixelCacheKey[abc, 16x9]", text);
  }
}
