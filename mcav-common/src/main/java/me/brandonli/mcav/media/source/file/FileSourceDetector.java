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
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.utils.SourceUtils;

/**
 * Detects paths of existing files or directories. Existence does not establish that a player can decode them.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public class FileSourceDetector implements SourceDetector<FileSource> {

  private static final String RAW_NULL_MESSAGE = "Raw must not be null!";

  /**
   * Constructs a new detector.
   *
   * @since 1.0.0
   */
  public FileSourceDetector() {}

  /**
   * Checks whether the string is a path that exists on disk.
   *
   * @param raw the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @throws NullPointerException if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    return SourceUtils.isPath(raw);
  }

  /**
   * Creates a file source for the path the string holds.
   *
   * @param raw the string entered by a user
   * @return the source
   * @throws java.nio.file.InvalidPathException if the string is not a valid path on this file system
   * @throws NullPointerException               if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public FileSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    final Path path = Path.of(raw);
    return FileSource.path(path);
  }

  /**
   * Returns {@link SourceDetector#HIGH_PRIORITY}, so an existing path wins over a URI reading of the same string.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}
