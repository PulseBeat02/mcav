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
 */
public class FileSourceDetector implements SourceDetector<FileSource> {

  /**
   * Constructs a new detector.
   */
  public FileSourceDetector() {}

  /**
   * {@inheritDoc}
   * @throws NullPointerException if {@code raw} is null
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    return SourceUtils.isPath(raw);
  }

  /**
   * {@inheritDoc}
   *
   * @throws java.nio.file.InvalidPathException if the string is not a valid path on this file system
   * @throws NullPointerException if {@code raw} is null
   */
  @Override
  public FileSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    final Path path = Path.of(raw);
    return FileSource.path(path);
  }

  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}
