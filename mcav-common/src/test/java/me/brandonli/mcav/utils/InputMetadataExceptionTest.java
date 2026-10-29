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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class InputMetadataExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final InputMetadataException exception = new InputMetadataException("Source video.mp4 has no video track!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Source video.mp4 has no video track!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final IOException readFailure = new IOException("connection reset!");
    final InputMetadataException exception = new InputMetadataException("Failed to read video metadata!", readFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Failed to read video metadata!", message);
    assertSame(readFailure, cause);
  }

  @Test
  void shouldBeAnMCAVException() {
    final InputMetadataException exception = new InputMetadataException("failure!");
    assertInstanceOf(MCAVException.class, exception, "media that cannot be read fails outside the caller's control");
  }
}
