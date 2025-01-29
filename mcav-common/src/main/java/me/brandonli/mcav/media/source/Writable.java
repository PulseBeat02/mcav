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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * The Writable interface defines a contract for objects that support
 * reading and writing operations tied to a specific path. It provides
 * default methods for obtaining input and output streams and includes
 * a utility method to create Writable instances.
 */
public interface Writable {
  /**
   * Retrieves the file path associated with this source.
   *
   * @return the {@code Path} representing the location of the file.
   */
  Path getPath();

  /**
   * Creates a new {@link InputStream} for reading from the path associated with this {@code Writable}
   * instance. The method uses the specified open options to configure the file read behavior.
   *
   * @param options the options specifying how the file is opened. These may include standard open
   *                options such as {@code READ} or {@code APPEND}, among others.
   * @return a new {@link InputStream} to read data from the associated path.
   * @throws IOException if an I/O error occurs during the creation of the {@link InputStream}.
   */
  default InputStream newInputStream(final OpenOption... options) throws IOException {
    return Files.newInputStream(this.getPath(), options);
  }

  /**
   * Creates a new {@link OutputStream} for writing to the path associated with this {@link Writable}.
   * The stream is opened with the defined {@link OpenOption}s, which control how the file is opened or created.
   *
   * @param options the options specifying how the file is opened. For example, {@link java.nio.file.StandardOpenOption#CREATE}
   *                to create a new file if it does not exist or {@link java.nio.file.StandardOpenOption#APPEND} to append to a file.
   * @return a new {@link OutputStream} for writing to the file.
   * @throws IOException if an I/O error occurs when opening or creating the output stream.
   */
  default OutputStream newOutputStream(final OpenOption... options) throws IOException {
    return Files.newOutputStream(this.getPath(), options);
  }

  /**
   * Creates a new {@link Writable} instance associated with the specified file path.
   *
   * @param path the {@link Path} representing the file location. Must not be null.
   * @return a new {@link Writable} instance associated with the specified file path.
   * @throws NullPointerException if the specified path is null.
   */
  static Writable path(final Path path) {
    Preconditions.checkNotNull(path);
    return new WritableImpl(path);
  }
}
