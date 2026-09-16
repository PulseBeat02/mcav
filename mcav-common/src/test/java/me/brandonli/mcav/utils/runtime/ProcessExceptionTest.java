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
package me.brandonli.mcav.utils.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ProcessException}.
 */
final class ProcessExceptionTest {

  @Test
  void keepsTheMessage() {
    final ProcessException exception = new ProcessException("ffmpeg exited with code 1");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("ffmpeg exited with code 1", message);
    assertNull(cause);
  }

  @Test
  void keepsTheMessageAndTheCause() {
    final IOException readFailure = new IOException("stream closed");
    final ProcessException exception = new ProcessException("The output of ffmpeg cannot be read", readFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("The output of ffmpeg cannot be read", message);
    assertSame(readFailure, cause);
  }

  @Test
  void isARuntimeException() {
    final ProcessException exception = new ProcessException("failure");
    assertInstanceOf(RuntimeException.class, exception, "a failing program is recoverable, so it is no Error");
  }
}
