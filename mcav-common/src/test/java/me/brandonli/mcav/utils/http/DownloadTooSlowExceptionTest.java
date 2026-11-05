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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class DownloadTooSlowExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final DownloadTooSlowException exception = new DownloadTooSlowException("The download took longer than the PT1S it may take!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("The download took longer than the PT1S it may take!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final IOException readFailure = new IOException("connection reset!");
    final DownloadTooSlowException exception = new DownloadTooSlowException("The download of a.zip is too slow!", readFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("The download of a.zip is too slow!", message);
    assertSame(readFailure, cause);
  }
}
