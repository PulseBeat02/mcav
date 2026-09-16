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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Identifies the pixel memory the packed pixels of a {@link MatImageBuffer} were read from.
 *
 * <p>OpenCV functions may reallocate or reshape a matrix in place, keeping the matrix header but moving its pixels.
 * When the address of the pixel data or the shape of the matrix no longer matches the key the cached pixels were read
 * with, the cache is stale even if nobody invalidated it.
 */
final class PixelCacheKey {

  private final long address;
  private final int width;
  private final int height;

  /**
   * Constructs a new key.
   *
   * @param address the address of the pixel data, not of the matrix header
   * @param width   the width of the matrix
   * @param height  the height of the matrix
   */
  PixelCacheKey(final long address, final int width, final int height) {
    this.address = address;
    this.width = width;
    this.height = height;
  }

  /**
   * Gets the width of the matrix.
   *
   * @return the width
   */
  int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the matrix.
   *
   * @return the height
   */
  int getHeight() {
    return this.height;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final PixelCacheKey key)) {
      return false;
    }
    return this.address == key.address && this.width == key.width && this.height == key.height;
  }

  @Override
  public int hashCode() {
    final int addressHash = Long.hashCode(this.address);
    return (addressHash * 31 + this.width) * 31 + this.height;
  }

  @Override
  public String toString() {
    final String hexAddress = Long.toHexString(this.address);
    return "PixelCacheKey[" + hexAddress + ", " + this.width + "x" + this.height + "]";
  }
}
