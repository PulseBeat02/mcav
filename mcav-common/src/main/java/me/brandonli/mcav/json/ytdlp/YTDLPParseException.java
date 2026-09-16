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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when yt-dlp fails for a URL, for example because the video is private, region locked, or the site is not
 * supported. The message contains the error yt-dlp printed.
 *
 * <p>This is a plain {@link RuntimeException} rather than an {@link IllegalArgumentException}: a well-formed URL can
 * still fail because of the state of the remote site, so the failure does not mean the caller passed a bad argument.
 * Keeping it apart from {@link IllegalArgumentException} also lets callers catch both in one multi-catch clause.
 */
public class YTDLPParseException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 6183930164275481521L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which contains the URL and what yt-dlp reported
   */
  YTDLPParseException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which contains the URL and what yt-dlp reported
   * @param cause   the underlying failure, such as the JSON parser error, or null if it is unknown
   */
  YTDLPParseException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
