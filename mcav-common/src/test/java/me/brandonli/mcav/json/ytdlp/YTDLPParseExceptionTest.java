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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link YTDLPParseException}.
 */
final class YTDLPParseExceptionTest {

  @Test
  void keepsTheMessage() {
    final YTDLPParseException exception = new YTDLPParseException("yt-dlp printed no metadata for https://example.com");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("yt-dlp printed no metadata for https://example.com", message);
    assertNull(cause);
  }

  @Test
  void keepsTheMessageAndTheCause() {
    final JsonSyntaxException parseFailure = new JsonSyntaxException("unterminated object");
    final YTDLPParseException exception = new YTDLPParseException("yt-dlp printed invalid metadata", parseFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("yt-dlp printed invalid metadata", message);
    assertSame(parseFailure, cause);
  }

  @Test
  void isARuntimeExceptionApartFromIllegalArguments() {
    final YTDLPParseException exception = new YTDLPParseException("failure");
    final Class<?> superclass = YTDLPParseException.class.getSuperclass();
    assertInstanceOf(RuntimeException.class, exception, "a failing URL is recoverable, so it is no Error");
    // a direct subclass of RuntimeException is no IllegalArgumentException: a well-formed URL can fail too, and
    // callers catch both types in one clause
    assertEquals(RuntimeException.class, superclass);
  }
}
