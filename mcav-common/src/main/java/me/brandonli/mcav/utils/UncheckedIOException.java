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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An unchecked exception that wraps an {@link java.io.IOException}.
 */
public class UncheckedIOException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 4402912769150611890L;

  /**
   * Constructs a new unchecked IOException with the specified detail message.
   * @param message the detail message, or {@code null} if none
   */
  public UncheckedIOException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new unchecked IOException with the specified detail message and cause.
   * @param message the detail message, or {@code null} if none
   * @param cause the cause of the exception, or {@code null} if none
   */
  public UncheckedIOException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
