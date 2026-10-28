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

import org.junit.jupiter.api.Test;

final class JarInjectorExceptionTest {

  @Test
  void shouldCarryTheMessageAndCauseOfInjectorExceptions() {
    final IllegalAccessException cause = new IllegalAccessException("Module is closed!");
    final JarInjectorException withCause = new JarInjectorException("Jars cannot be injected!", cause);
    final JarInjectorException withoutCause = new JarInjectorException("Jars cannot be injected!");
    final String message = withCause.getMessage();
    final Throwable actualCause = withCause.getCause();
    final String otherMessage = withoutCause.getMessage();
    final Throwable missingCause = withoutCause.getCause();
    assertEquals("Jars cannot be injected!", message);
    assertSame(cause, actualCause);
    assertEquals("Jars cannot be injected!", otherMessage);
    assertNull(missingCause);
    assertInstanceOf(UnsupportedOperationException.class, withCause);
  }
}
