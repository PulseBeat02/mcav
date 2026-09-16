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

import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable width and height in pixels.
 */
public final class Dimension {

  /**
   * The dimension with zero width and height, used where no size is set.
   */
  public static final Dimension NONE = new Dimension(0, 0);

  private final int width;
  private final int height;

  /**
   * Constructs a dimension.
   *
   * @param width  the width in pixels, not negative
   * @param height the height in pixels, not negative
   */
  public Dimension(final int width, final int height) {
    Preconditions.checkArgument(width >= 0, "Width must not be negative but was %s", width);
    Preconditions.checkArgument(height >= 0, "Height must not be negative but was %s", height);
    this.width = width;
    this.height = height;
  }

  /**
   * Creates a dimension.
   *
   * @param width  the width in pixels, not negative
   * @param height the height in pixels, not negative
   * @return the dimension
   */
  public static Dimension of(final int width, final int height) {
    return new Dimension(width, height);
  }

  /**
   * Gets the width.
   *
   * @return the width in pixels
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height.
   *
   * @return the height in pixels
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Checks whether either side is zero.
   *
   * @return true if the dimension covers no pixels
   */
  public boolean isEmpty() {
    return this.width == 0 || this.height == 0;
  }

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

  @Override
  public int hashCode() {
    return this.width * 31 + this.height;
  }

  @Override
  public String toString() {
    return this.width + "x" + this.height;
  }
}
