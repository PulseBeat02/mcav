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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link HttpModule} and {@link HttpException}.
 */
final class HttpModuleTest {

  @Test
  void shouldBeNamedHttp() {
    final HttpModule module = new HttpModule();
    final String name = module.getModuleName();
    assertEquals("http", name);
  }

  @Test
  void shouldStartAndStopWithoutHoldingResources() {
    final HttpModule module = new HttpModule();
    assertDoesNotThrow(module::start);
    assertDoesNotThrow(module::stop);
  }

  @Test
  void shouldKeepTheMessageInExceptions() {
    final HttpException exception = new HttpException("cannot start");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot start", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseInExceptions() {
    final IOException portInUse = new IOException("port in use");
    final HttpException exception = new HttpException("cannot start", portInUse);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot start", message);
    assertSame(portInUse, cause);
  }

  @Test
  void shouldMakeExceptionsRuntimeExceptions() {
    final HttpException exception = new HttpException("cannot start");
    assertInstanceOf(RuntimeException.class, exception, "a server that cannot start is recoverable, so it is no Error");
  }
}
