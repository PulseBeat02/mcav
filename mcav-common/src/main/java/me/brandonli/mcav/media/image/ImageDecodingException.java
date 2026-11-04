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
package me.brandonli.mcav.media.image;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when an image file cannot be opened or decoded, for example because it is damaged or in a format FFmpeg does
 * not know.
 *
 * @since 1.0.0
 */
public final class ImageDecodingException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 7693521155387399968L;

  ImageDecodingException(final String message) {
    super(message);
  }

  ImageDecodingException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
