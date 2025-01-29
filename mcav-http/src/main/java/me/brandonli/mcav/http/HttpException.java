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
package me.brandonli.mcav.http;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents an exception that occurs during HTTP operations.
 * This exception extends {@link AssertionError} and is used to signal HTTP-related
 * errors encountered during execution.
 * <p>
 * The {@code HttpException} is a runtime exception that allows for propagation
 * of error messages associated with HTTP-related issues.
 */
public class HttpException extends AssertionError {

  private static final long serialVersionUID = -4536819561346624904L;

  HttpException(final @Nullable String message) {
    super(message);
  }
}
