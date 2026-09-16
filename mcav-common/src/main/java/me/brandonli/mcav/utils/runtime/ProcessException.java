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
package me.brandonli.mcav.utils.runtime;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when an external program fails, either because it exits with a non-zero exit code or because its output
 * cannot be read.
 *
 * <p>This is a plain {@link RuntimeException}: the failure belongs to the external program and its input, such as a
 * source FFmpeg cannot decode, not to the state of the caller or to an unsupported operation. It is unchecked so that
 * callers who cannot handle a failing program need no {@code throws} clause, while callers who can, for example by
 * skipping the source, catch it explicitly.
 */
public class ProcessException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = -9183271875521286701L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which usually contains the exit code and the error output of the program
   */
  ProcessException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which describes what went wrong while running the program
   * @param cause   the underlying failure, or null if it is unknown
   */
  ProcessException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
