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
package me.brandonli.mcav.utils;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An unchecked exception that wraps an {@link java.io.IOException}.
 * This class extends {@link AssertionError} and can be used to
 * indicate that an I/O operation has failed and should be treated as
 * an unexpected fatal error in the application flow.
 * <p>
 * This exception is primarily intended for scenarios where an {@link java.io.IOException}
 * must be thrown in a context where only unchecked exceptions are permitted,
 * for instance in stream processing pipelines or lambda expressions.
 */
public class UncheckedIOException extends AssertionError {

  private static final long serialVersionUID = 4402912769150611890L;

  public UncheckedIOException(final @Nullable String message) {
    super(message);
  }

  public UncheckedIOException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
