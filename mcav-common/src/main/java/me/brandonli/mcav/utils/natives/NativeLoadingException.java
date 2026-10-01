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
package me.brandonli.mcav.utils.natives;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when a native library cannot be extracted or loaded.
 *
 * <p>This is an {@link IllegalStateException}: the environment of the library is not in a state that lets it work,
 * because the natives for this platform are missing or cannot be linked. The caller did nothing wrong, and retrying
 * the same call in the same environment fails again. It can be caught as a {@link RuntimeException}, rather than as the {@link LinkageError} it usually wraps,
 * so applications can report an unavailable feature separately from fatal virtual-machine failures.
 */
public class NativeLoadingException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = -1718334825313639127L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message naming the library that could not be loaded, or null
   */
  public NativeLoadingException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message naming the library that could not be loaded, or null
   * @param cause   the underlying failure, usually a {@link LinkageError}, or null if it is unknown
   */
  public NativeLoadingException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
