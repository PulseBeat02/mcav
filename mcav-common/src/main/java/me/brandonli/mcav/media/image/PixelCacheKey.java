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

import com.google.common.base.MoreObjects;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

// The pixel memory a MatImageBuffer's cache was read from; OpenCV may move pixels under a kept header, so a mismatch means a stale cache.
final class PixelCacheKey {

  private final long address;
  private final int width;
  private final int height;

  PixelCacheKey(final long address, final int width, final int height) {
    this.address = address;
    this.width = width;
    this.height = height;
  }

  int getWidth() {
    return this.width;
  }

  int getHeight() {
    return this.height;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final PixelCacheKey pixelCacheKey)) {
      return false;
    }

    return this.address == pixelCacheKey.address && this.width == pixelCacheKey.width && this.height == pixelCacheKey.height;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.address, this.width, this.height);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("address", this.address).add("width", this.width).add("height", this.height).toString();
  }
}
