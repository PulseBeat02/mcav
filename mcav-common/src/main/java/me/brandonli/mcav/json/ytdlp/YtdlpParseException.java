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
package me.brandonli.mcav.json.ytdlp;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when yt-dlp exits with a failure or returns missing or malformed metadata.
 *
 * <p>A nonzero exit reports the URL and yt-dlp's error output, falling back to its standard output when stderr
 * is blank. Invalid JSON reports the URL and retains the parse failure as its cause. This {@link MCAVException}
 * is separate from the exceptions of installing and running yt-dlp, such as
 * {@link me.brandonli.mcav.utils.runtime.ProcessException}, and from
 * {@link IllegalArgumentException} for an invalid input scheme.
 *
 * @since 1.0.0
 */
public final class YtdlpParseException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 6183930164275481521L;

  YtdlpParseException(final String message) {
    super(message);
  }

  YtdlpParseException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
