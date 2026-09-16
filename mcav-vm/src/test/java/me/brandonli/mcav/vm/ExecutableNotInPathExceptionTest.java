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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ExecutableNotInPathException}.
 */
final class ExecutableNotInPathExceptionTest {

  @Test
  void namesTheMissingProgram() {
    final ExecutableNotInPathException exception = new ExecutableNotInPathException("qemu-system-arm");
    final String message = exception.getMessage();
    assertEquals("The program qemu-system-arm is not installed or not on the PATH", message);
    assertInstanceOf(RuntimeException.class, exception);
  }

  @Test
  void rejectsANullProgramName() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      throw new ExecutableNotInPathException(null);
    });
    final String message = exception.getMessage();
    assertEquals("Executable must not be null", message);
  }
}
