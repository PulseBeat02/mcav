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
 * Thrown when the metadata of a media source cannot be read, for example because the source cannot be opened or has
 * no track of the requested kind.
 *
 * <p>This is a plain {@link RuntimeException}: the failure comes from the content of the media or from reading it,
 * which the caller usually cannot check in advance, so it is neither an {@link IllegalArgumentException} nor an
 * {@link IllegalStateException}. Callers that can recover, for example by skipping the source, catch it explicitly.
 */
public class InputMetadataException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = -2964304434518823943L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which names the source and what is missing
   */
  InputMetadataException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which names the source and why it could not be read
   * @param cause   the underlying failure, such as the decoder error, or null if it is unknown
   */
  InputMetadataException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
