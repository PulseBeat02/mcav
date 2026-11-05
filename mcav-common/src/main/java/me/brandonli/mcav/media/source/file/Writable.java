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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import me.brandonli.mcav.utils.FileOperationException;

/**
 * A file that can be read from and written to, for example to save a downloaded stream.
 *
 * <p>Implementations in this library are immutable and thread-safe.
 *
 * @since 1.0.0
 */
public interface Writable {
  /**
   * Creates a handle for a file. The file does not have to exist yet.
   *
   * @param path the path of the file
   * @return the handle
   * @throws NullPointerException if {@code path} is null
   * @since 1.0.0
   */
  static Writable path(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    return new DefaultWritable(path);
  }

  /**
   * Returns the path of the file.
   *
   * @return the path
   * @since 1.0.0
   */
  Path getPath();

  /**
   * Opens the file for reading.
   *
   * @param options the open options, see {@link Files#newInputStream(Path, OpenOption...)}
   * @return the stream, which the caller must close
   * @throws FileOperationException        if the file cannot be opened
   * @throws UnsupportedOperationException if an option is unsupported by the file-system provider
   * @throws IllegalArgumentException      if the options are an invalid combination
   * @throws NullPointerException          if {@code options} is null
   * @since 1.0.0
   */
  default InputStream newInputStream(final OpenOption... options) {
    Preconditions.checkNotNull(options, "Options must not be null!");

    final Path path = this.getPath();
    try {
      return Files.newInputStream(path, options);
    } catch (final IOException exception) {
      final String message = "File %s could not be opened for reading!".formatted(path);
      throw new FileOperationException(message, exception);
    }
  }

  /**
   * Opens the file for writing.
   *
   * @param options the open options, see {@link Files#newOutputStream(Path, OpenOption...)}
   * @return the stream, which the caller must close
   * @throws FileOperationException        if the file cannot be opened
   * @throws UnsupportedOperationException if an option is unsupported by the file-system provider
   * @throws IllegalArgumentException      if the options are an invalid combination
   * @throws NullPointerException          if {@code options} is null
   * @since 1.0.0
   */
  default OutputStream newOutputStream(final OpenOption... options) {
    Preconditions.checkNotNull(options, "Options must not be null!");

    final Path path = this.getPath();
    try {
      return Files.newOutputStream(path, options);
    } catch (final IOException exception) {
      final String message = "File %s could not be opened for writing!".formatted(path);
      throw new FileOperationException(message, exception);
    }
  }
}
