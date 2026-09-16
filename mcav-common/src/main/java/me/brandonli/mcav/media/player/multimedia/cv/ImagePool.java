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

/**
 * The images a playback session reuses for its video frames.
 *
 * <p>The decoding thread copies every frame into an image it takes from the pool, and the rendering thread hands the
 * image back once the pipeline is done with it. A session therefore allocates only as many images as frames can be
 * in flight at once, instead of one image per frame. The pool keeps at most its capacity of images; further images
 * are released, and so is every image handed back after the pool was closed.
 *
 * <p>All methods are thread-safe.
 */
final class ImagePool {

  private final int capacity;
  private final Deque<MatImageBuffer> free;

  private boolean closed;

  /**
   * Constructs a new, empty pool.
   *
   * @param capacity the largest number of unused images the pool keeps
   */
  ImagePool(final int capacity) {
    this.capacity = capacity;
    this.free = new ArrayDeque<>();
  }

  /**
   * Takes an unused image out of the pool, preferring the one handed back last, whose memory is most likely still in
   * the processor caches.
   *
   * @return an image of any size, or {@code null} if the pool holds no unused image
   */
  synchronized @Nullable MatImageBuffer acquire() {
    return this.free.pollFirst();
  }

  /**
   * Hands an image back to the pool once nobody uses it anymore. The image is released instead if the pool is full or
   * closed.
   *
   * @param image the image, which the caller must not use afterward
   */
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

  /**
   * Closes the pool and releases every unused image. Images handed back later are released right away.
   */
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

  /**
   * Gets the number of unused images in the pool.
   *
   * @return the number of images {@link #acquire()} can hand out without allocating
   */
  synchronized int getUnusedCount() {
    return this.free.size();
  }
}
