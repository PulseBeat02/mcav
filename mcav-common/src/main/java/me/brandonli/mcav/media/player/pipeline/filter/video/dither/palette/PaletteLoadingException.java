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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when the map palette resource is missing or malformed.
 *
 * <p>This is an {@link MCAVException}: the palette ships inside the library, so a missing or malformed
 * resource means the library itself is packaged in a broken state. No argument of the caller is at fault, and the
 * failure persists for as long as the same library jar is used.
 *
 * @since 1.0.0
 */
public final class PaletteLoadingException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 3449385531808176439L;

  PaletteLoadingException(final String message) {
    super(message);
  }

  PaletteLoadingException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
