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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.brandonli.mcav.media.image.MatImageBuffer;
import org.checkerframework.checker.nullness.qual.Nullable;

// Reuses the frame images of a session between its decoding and rendering threads, keeping at most capacity unused
// images. All methods are thread-safe.
final class ImagePool {

  private final int capacity;
  private final Deque<MatImageBuffer> free;

  private boolean closed;

  ImagePool(final int capacity) {
    this.capacity = capacity;
    this.free = new ArrayDeque<>();
  }

  synchronized @Nullable MatImageBuffer acquire() {
    return this.free.pollFirst();
  }

  // Hands an image back to the pool once nobody uses it anymore. The image is released instead if the pool is full or
  // closed.
  void recycle(final MatImageBuffer image) {
    final boolean kept = this.keep(image);
    if (!kept) {
      image.release();
    }
  }

  private synchronized boolean keep(final MatImageBuffer image) {
    final int unused = this.free.size();
    if (this.closed || unused >= this.capacity) {
      return false;
    }

    this.free.addFirst(image);

    return true;
  }

  // Closes the pool and releases every unused image. Images handed back later are released right away.
  void close() {
    final List<MatImageBuffer> unused = this.closeAndDrain();
    for (final MatImageBuffer image : unused) {
      image.release();
    }
  }

  private synchronized List<MatImageBuffer> closeAndDrain() {
    this.closed = true;
    final List<MatImageBuffer> unused = new ArrayList<>(this.free);
    this.free.clear();

    return unused;
  }

  synchronized int getUnusedCount() {
    return this.free.size();
  }
}
