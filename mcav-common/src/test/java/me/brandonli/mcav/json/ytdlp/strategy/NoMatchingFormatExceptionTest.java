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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class NoMatchingFormatExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final NoMatchingFormatException exception = new NoMatchingFormatException("No video stream matches the strategy!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("No video stream matches the strategy!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final IllegalStateException selectionFailure = new IllegalStateException("Format list could not be read!");
    final NoMatchingFormatException exception = new NoMatchingFormatException("No audio stream matches the strategy!", selectionFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("No audio stream matches the strategy!", message);
    assertSame(selectionFailure, cause);
  }

  @Test
  void shouldBeAnMCAVException() {
    final NoMatchingFormatException exception = new NoMatchingFormatException("Failure!");
    assertInstanceOf(MCAVException.class, exception, "a missing format is recoverable, so it is no Error");
  }
}
