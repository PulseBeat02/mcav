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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown to indicate that an error occurred during the loading or processing of a palette.
 * This exception extends {@link AssertionError}, as it is expected to occur only in cases
 * where a critical assertion about the correctness or state of the palette has failed.
 *
 * <p>
 * This exception is used to signal issues such as invalid palette configurations,
 * unsupported formats, or other errors related to the initialization or usage of a palette.
 *
 * <p>
 * The {@code PaletteLoadingException} is intended to be used internally within the framework
 * managing palette operations, providing a clear indication of palette-related issues that
 * require debugging or corrective actions.
 *
 * @see Palette
 */
public class PaletteLoadingException extends AssertionError {

  private static final long serialVersionUID = 3449385531808176439L;

  PaletteLoadingException(final @Nullable String message) {
    super(message);
  }

  PaletteLoadingException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
