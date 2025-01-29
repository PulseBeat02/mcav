/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.nio.file.Path;

/**
 * An implementation of the {@link FileSource} interface that represents a file source
 * based on a specified file path. This class provides the concrete functionality for
 * interacting with the file system through its associated {@code Path}.
 * <p>
 * This class is immutable and thread-safe, as the {@code Path} it encapsulates is final,
 * ensuring consistent behavior once an instance of this class is created.
 */
public final class FileSourceImpl implements FileSource {

  final Path path;

  FileSourceImpl(final Path path) {
    this.path = path;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Path getPath() {
    return this.path;
  }
}
