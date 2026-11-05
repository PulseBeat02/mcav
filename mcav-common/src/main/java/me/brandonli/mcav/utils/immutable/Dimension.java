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
package me.brandonli.mcav.utils.immutable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable width and height in pixels.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class Dimension {

  /**
   * The dimension with zero width and height, used where no size is set.
   *
   * @since 1.0.0
   */
  public static final Dimension NONE = new Dimension(0, 0);

  private final int width;
  private final int height;

  /**
   * Creates a dimension.
   *
   * @param width  the width in pixels, not negative
   * @param height the height in pixels, not negative
   * @throws IllegalArgumentException if either dimension is negative
   * @since 1.0.0
   */
  public Dimension(final int width, final int height) {
    Preconditions.checkArgument(width >= 0, "Width %s must not be negative!", width);
    Preconditions.checkArgument(height >= 0, "Height %s must not be negative!", height);

    this.width = width;
    this.height = height;
  }

  /**
   * Creates a dimension.
   *
   * @param width  the width in pixels, not negative
   * @param height the height in pixels, not negative
   * @return the dimension
   * @throws IllegalArgumentException if either dimension is negative
   * @since 1.0.0
   */
  public static Dimension of(final int width, final int height) {
    return new Dimension(width, height);
  }

  /**
   * Returns the width.
   *
   * @return the width in pixels
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Returns the height.
   *
   * @return the height in pixels
   * @since 1.0.0
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Returns whether either side is zero.
   *
   * @return whether the dimension covers no pixels
   * @since 1.0.0
   */
  public boolean isEmpty() {
    return this.width == 0 || this.height == 0;
  }

  /**
   * Returns whether {@code other} is a dimension with the same width and the height.
   *
   * @param other the object to compare with
   * @return whether both match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Dimension dimension)) {
      return false;
    }

    return this.width == dimension.width && this.height == dimension.height;
  }

  /**
   * Returns a hash code built from the width and the height.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.width, this.height);
  }

  /**
   * Returns a debugging representation with the width and the height.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("width", this.width).add("height", this.height).toString();
  }
}
