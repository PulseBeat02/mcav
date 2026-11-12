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
package me.brandonli.mcav.media.player.multimedia;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

final class DefaultExceptionHandlerTest {

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogTheMessageAndTheFailureAtErrorLevelByDefault() {
    final ExceptionHandler holder = ExceptionHandler.createDefault();
    final BiConsumer<String, Throwable> handler = holder.getExceptionHandler();
    final IllegalStateException failure = new IllegalStateException("the network stream broke!");
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      handler.accept("Failed to read a frame.", failure);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final String messageLine = "ERROR %s - Failed to read a frame.".formatted(DefaultExceptionHandler.class.getName());

    final boolean loggedTheMessage = log.contains(messageLine);
    final boolean loggedTheFailure = log.contains("java.lang.IllegalStateException: the network stream broke!");
    assertTrue(loggedTheMessage, log);
    assertTrue(loggedTheFailure, log);
  }

  @Test
  void shouldRejectANullHandler() {
    final ExceptionHandler holder = ExceptionHandler.createDefault();
    assertThrows(NullPointerException.class, () -> holder.setExceptionHandler(null));
  }
}
