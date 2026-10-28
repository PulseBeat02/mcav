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
package me.brandonli.mcav.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class InstallationExceptionTest {

  private static final String COPY_FAILURE_MESSAGE = "Jars cannot be copied!";

  @Test
  void shouldKeepTheMessageOfInstallationExceptions() {
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals(COPY_FAILURE_MESSAGE, message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseOfInstallationExceptions() {
    final IOException copyFailure = new IOException("Disk is full!");
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE, copyFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals(COPY_FAILURE_MESSAGE, message);
    assertSame(copyFailure, cause);
  }

  @Test
  void shouldMakeInstallationExceptionsRuntimeExceptions() {
    final InstallationException exception = new InstallationException(COPY_FAILURE_MESSAGE);
    assertInstanceOf(RuntimeException.class, exception, "a failed installation is recoverable, so it is no Error");
  }
}
