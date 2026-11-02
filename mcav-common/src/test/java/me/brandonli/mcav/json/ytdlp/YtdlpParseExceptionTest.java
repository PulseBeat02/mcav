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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonSyntaxException;
import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class YtdlpParseExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final YtdlpParseException exception = new YtdlpParseException("URL https://example.com got no metadata from yt-dlp!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("URL https://example.com got no metadata from yt-dlp!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final JsonSyntaxException parseFailure = new JsonSyntaxException("unterminated object!");
    final YtdlpParseException exception = new YtdlpParseException("yt-dlp printed invalid metadata!", parseFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("yt-dlp printed invalid metadata!", message);
    assertSame(parseFailure, cause);
  }

  @Test
  void shouldBeAnMCAVExceptionApartFromIllegalArguments() {
    final Class<?> superclass = YtdlpParseException.class.getSuperclass();
    assertEquals(MCAVException.class, superclass);
  }
}
