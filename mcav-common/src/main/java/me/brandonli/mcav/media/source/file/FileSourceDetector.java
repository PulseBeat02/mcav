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

import java.nio.file.Path;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.utils.SourceUtils;

/**
 * A source detector for file sources.
 */
public class FileSourceDetector implements SourceDetector<FileSource> {

  /**
   * Constructs a new {@link FileSourceDetector}.
   */
  public FileSourceDetector() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    return SourceUtils.isPath(raw);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FileSource createSource(final String raw) {
    final Path path = Path.of(raw);
    return FileSource.path(path);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPriority() {
    return SourceDetector.HIGH_PRIORITY;
  }
}
