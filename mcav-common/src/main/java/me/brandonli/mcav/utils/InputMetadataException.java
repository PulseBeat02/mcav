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
 * Thrown when the metadata of a media source cannot be read, for example because the source cannot be opened or has
 * no track of the requested kind.
 *
 * <p>This is an {@link MCAVException}: the failure comes from the content of the media or from reading it,
 * which the caller usually cannot check in advance, so it is neither an {@link IllegalArgumentException} nor an
 * {@link IllegalStateException}. Callers that can recover, for example by skipping the source, catch it explicitly.
 *
 * @since 1.0.0
 */
public final class InputMetadataException extends MCAVException {

  @Serial
  private static final long serialVersionUID = -2964304434518823943L;

  InputMetadataException(final String message) {
    super(message);
  }

  InputMetadataException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
