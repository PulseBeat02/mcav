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
package me.brandonli.mcav.utils;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when a zip archive contains an entry that is not safe to extract, such as one that escapes the target
 * directory or exceeds the size limits.
 *
 * <p>This is an {@link MCAVException}: the archive itself is unsafe, which is neither a state of the caller
 * nor an I/O failure that retrying could fix. It deliberately does not extend {@link FileOperationException}, so a
 * rejected archive can be told apart from one that merely could not be read, and both can be caught in one
 * multi-catch clause.
 *
 * @since 1.0.0
 */
public final class ZipEntryIntegrityException extends MCAVException {

  @Serial
  private static final long serialVersionUID = -949105092892572153L;

  ZipEntryIntegrityException(final String message) {
    super(message);
  }

  ZipEntryIntegrityException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
