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
package me.brandonli.mcav.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class ModuleExceptionTest {

  @Test
  void shouldCreateExceptionsWithMessageAndCause() {
    final IllegalStateException cause = new IllegalStateException("cause!");
    final ModuleException withMessage = new ModuleException("Message!");
    final ModuleException withCause = new ModuleException("Wrapped!", cause);
    final ModuleException withNulls = new ModuleException(null, null);
    final String message = withMessage.getMessage();
    final Throwable noCause = withMessage.getCause();
    final String wrappedMessage = withCause.getMessage();
    final Throwable wrappedCause = withCause.getCause();
    final String nullMessage = withNulls.getMessage();
    assertEquals("Message!", message);
    assertNull(noCause);
    assertEquals("Wrapped!", wrappedMessage);
    assertSame(cause, wrappedCause);
    assertNull(nullMessage);
    assertInstanceOf(MCAVException.class, withMessage, "a module failure is recoverable, so it is no Error");
  }
}
