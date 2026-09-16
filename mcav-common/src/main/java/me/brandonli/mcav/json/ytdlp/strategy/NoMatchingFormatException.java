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
package me.brandonli.mcav.json.ytdlp.strategy;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when none of the streams yt-dlp found matches a {@link FormatStrategy}.
 *
 * <p>This is a plain {@link RuntimeException}: whether a stream matches depends on what the remote site offers for
 * the URL, not on the state of the selector or on a bad argument, so {@link IllegalStateException} and
 * {@link IllegalArgumentException} would both be misleading. Callers that can fall back, for example to an audio-only
 * strategy, catch it explicitly.
 */
public class NoMatchingFormatException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = -8093712854432123795L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which names the kind of stream and the media that was searched
   */
  NoMatchingFormatException(final @Nullable String message) {
    super(message);
  }
}
