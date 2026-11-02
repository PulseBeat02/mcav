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

import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;

final class ExecutableNotInPathExceptionTest {

  private static final String PROGRAM = "qemu-system-riscv64";

  private static final String NULL_EXECUTABLE_MESSAGE = "Executable must not be null!";

  private static final Platform UNSUPPORTED_PLATFORM = Platform.ofPlatform(Os.FREEBSD, Arch.ARM, Bits.BITS_64);

  @Test
  void shouldNameTheMissingProgram() {
    final ExecutableNotInPathException exception = new ExecutableNotInPathException("qemu-system-arm");
    final String message = exception.getMessage();
    assertEquals("Program qemu-system-arm is not installed or not on the PATH!", message);
    assertInstanceOf(VmException.class, exception);
  }

  @Test
  void shouldRejectANullProgramName() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      throw new ExecutableNotInPathException(null);
    });
    final String message = exception.getMessage();
    assertEquals(NULL_EXECUTABLE_MESSAGE, message);
  }

  @Test
  void shouldNameThePlatformThatMcavCannotInstallTheProgramOn() {
    final ExecutableNotInPathException exception = new ExecutableNotInPathException(PROGRAM, UNSUPPORTED_PLATFORM);

    final String message = exception.getMessage();
    assertEquals("Program qemu-system-riscv64 is not installed or not on the PATH, and on FREEBSD ARM MCAV cannot install it!", message);
  }

  @Test
  void shouldRejectANullProgramNameWithAPlatform() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      throw new ExecutableNotInPathException(null, UNSUPPORTED_PLATFORM);
    });
    final String message = exception.getMessage();
    assertEquals(NULL_EXECUTABLE_MESSAGE, message);
  }

  @Test
  void shouldRejectANullPlatform() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      throw new ExecutableNotInPathException(PROGRAM, null);
    });
    final String message = exception.getMessage();
    assertEquals("Platform must not be null!", message);
  }
}
