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
package me.brandonli.mcav.installer;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when dependencies cannot be resolved, downloaded, or verified.
 *
 * <p>This is a plain {@link RuntimeException}: an installation depends on the network, the Maven repositories, and
 * the file system, none of which the caller controls, so a failure is neither a programming error nor a state of the
 * installer, and trying again later may succeed. It is not a {@link java.io.UncheckedIOException}, because a failed
 * resolution has no {@link java.io.IOException} to wrap, and it is never an {@link Error}, so the plugin or
 * application that bootstraps mcav can catch it and shut down cleanly.
 */
public class InstallationException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 6370074734200515542L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which says what could not be installed
   */
  InstallationException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which says what could not be installed
   * @param cause   the failure that caused this exception, such as a failed resolution, or null if it is unknown
   */
  InstallationException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
