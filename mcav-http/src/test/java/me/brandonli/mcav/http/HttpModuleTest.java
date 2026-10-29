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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class HttpModuleTest {

  private static final String LOCALHOST = "localhost";

  private static final int EPHEMERAL_PORT = 0;

  private static final List<String> LOOPBACK = List.of("server.address=127.0.0.1");

  @Test
  void shouldBeNamedHttp() {
    final HttpModule module = new HttpModule();
    final String name = module.getModuleName();
    assertEquals("http", name);
  }

  @Test
  void shouldLeaveTheServersYouStartedRunningWhenStopped() {
    final HttpModule module = new HttpModule();
    final SpringHttpResult server = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, null, LOOPBACK);
    module.start();
    server.start();
    try {
      module.stop();

      final boolean running = server.isRunning();
      assertTrue(running);
    } finally {
      server.stop();
    }
  }

  @Test
  void shouldKeepTheMessageInExceptions() {
    final HttpException exception = new HttpException("cannot start!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot start!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseInExceptions() {
    final IOException portInUse = new IOException("port in use!");
    final HttpException exception = new HttpException("cannot start!", portInUse);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("cannot start!", message);
    assertSame(portInUse, cause);
  }

  @Test
  void shouldMakeExceptionsMCAVExceptions() {
    final HttpException exception = new HttpException("cannot start!");
    assertInstanceOf(MCAVException.class, exception, "a caller handles the failures of every module through one type");
  }
}
