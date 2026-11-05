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
package me.brandonli.mcav.media.source.file;

import com.google.common.base.MoreObjects;
import java.nio.file.Path;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link Writable}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class DefaultWritable implements Writable {

  private final Path path;

  DefaultWritable(final Path path) {
    this.path = path;
  }

  /**
   * Returns the path of the file this writable target writes.
   *
   * @return the path
   * @since 1.0.0
   */
  @Override
  public Path getPath() {
    return this.path;
  }

  /**
   * Returns whether {@code other} is a writable target with the same path.
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

    if (!(other instanceof final DefaultWritable writable)) {
      return false;
    }

    return this.path.equals(writable.path);
  }

  /**
   * Returns a hash code built from the path.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.path);
  }

  /**
   * Returns a debugging representation with the path.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("path", this.path).toString();
  }
}
