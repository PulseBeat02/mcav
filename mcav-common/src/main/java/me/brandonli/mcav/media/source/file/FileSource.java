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

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import me.brandonli.mcav.media.source.StaticSource;

/**
 * A media file on the local file system.
 *
 * <p>Implementations in this library are immutable and thread-safe.
 *
 * @since 1.0.0
 */
public interface FileSource extends StaticSource {
  /**
   * Creates a source for a file. The file does not have to exist yet.
   *
   * @param path the path of the file
   * @return the source
   * @throws NullPointerException if {@code path} is null
   * @since 1.0.0
   */
  static FileSource path(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    return new DefaultFileSource(path);
  }

  /**
   * Returns the path of the file.
   *
   * @return the path
   * @since 1.0.0
   */
  Path getPath();

  /**
   * Creates a handle for reading from and writing to the file.
   *
   * @return the writable handle
   * @since 1.0.0
   */
  default Writable createWritable() {
    final Path path = this.getPath();
    return new DefaultWritable(path);
  }

  /**
   * Returns {@code file}, the name that log messages give file sources.
   *
   * @return the type name
   * @since 1.0.0
   */
  @Override
  default String getName() {
    return "file";
  }

  /**
   * Returns the path of this file source as a string.
   *
   * @return the resource string
   * @since 1.0.0
   */
  @Override
  default String getResource() {
    final Path path = this.getPath();
    return path.toString();
  }
}
