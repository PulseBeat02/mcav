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
package me.brandonli.mcav.media.player.multimedia.cv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.image.MatImageBuffer;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ImagePool}.
 */
final class ImagePoolTest {

  private static MatImageBuffer image() {
    final ImageBuffer created = ImageBuffer.bytes(new byte[3], 1, 1);
    return (MatImageBuffer) created;
  }

  @Test
  void handsOutTheImagesThatWereHandedBackLatestFirst() {
    final ImagePool pool = new ImagePool(2);
    final MatImageBuffer first = image();
    final MatImageBuffer second = image();
    final MatImageBuffer empty = pool.acquire();
    pool.recycle(first);
    pool.recycle(second);
    final MatImageBuffer latest = pool.acquire();
    final MatImageBuffer earlier = pool.acquire();
    final MatImageBuffer none = pool.acquire();
    assertNull(empty, "a new pool holds no images");
    assertSame(second, latest);
    assertSame(first, earlier);
    assertNull(none);
    first.release();
    second.release();
  }

  @Test
  void releasesImagesBeyondItsCapacity() {
    final ImagePool pool = new ImagePool(1);
    final MatImageBuffer kept = image();
    final MatImageBuffer surplus = image();
    pool.recycle(kept);
    pool.recycle(surplus);
    final int unused = pool.getUnusedCount();
    final int keptWidth = kept.getWidth();
    assertEquals(1, unused);
    assertEquals(1, keptWidth);
    assertThrows(IllegalStateException.class, surplus::getWidth, "an image the pool has no room for is released");
    pool.close();
  }

  @Test
  void closingReleasesTheUnusedImagesAndEveryImageHandedBackLater() {
    final ImagePool pool = new ImagePool(4);
    final MatImageBuffer unused = image();
    final MatImageBuffer late = image();
    pool.recycle(unused);
    pool.close();
    pool.recycle(late);
    final int remaining = pool.getUnusedCount();
    final MatImageBuffer acquired = pool.acquire();
    assertEquals(0, remaining);
    assertNull(acquired);
    assertThrows(IllegalStateException.class, unused::getWidth);
    assertThrows(IllegalStateException.class, late::getWidth);
  }
}
