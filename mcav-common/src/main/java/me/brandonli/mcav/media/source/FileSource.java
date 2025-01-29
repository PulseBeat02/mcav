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
package me.brandonli.mcav.media.source;

import com.google.common.base.Preconditions;
import java.nio.file.Path;

/**
 * Represents a source that is backed by a file on the filesystem.
 */
public interface FileSource extends StaticSource {
  /**
   * Retrieves the associated file path of this source.
   *
   * @return the {@code Path} representing the file location.
   */
  Path getPath();

  default Writable createWritable() {
    return new WritableImpl(this.getPath());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  default String getName() {
    return "file";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  default String getResource() {
    return this.getPath().toString();
  }

  /**
   * Creates a new {@link FileSource} instance for the specified file path.
   *
   * @param path the {@link Path} representing the file location. Must not be null.
   * @return a new {@link FileSource} instance associated with the specified file path.
   * @throws NullPointerException if the specified path is null.
   */
  static FileSource path(final Path path) {
    Preconditions.checkNotNull(path);
    return new FileSourceImpl(path);
  }
}
