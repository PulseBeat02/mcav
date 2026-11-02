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

import com.google.common.base.Preconditions;
import java.io.Serial;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;

/**
 * Thrown when a program that must be installed on the machine, such as QEMU, is not on the {@code PATH} or in one of
 * the folders {@link ExecutableFinder} also searches, and MCAV cannot install it on this platform.
 *
 * <p>It is a {@link VmException}, so catching that handles it too.
 *
 * @since 1.0.0
 */
public final class ExecutableNotInPathException extends VmException {

  @Serial
  private static final long serialVersionUID = 4163726305184327011L;

  private static final String EXECUTABLE_NULL_MESSAGE = "Executable must not be null!";

  /**
   * Creates an exception for a program that is not installed or not on the {@code PATH}.
   *
   * @param executable the name of the missing program
   * @throws NullPointerException if the name is null
   * @since 1.0.0
   */
  public ExecutableNotInPathException(final String executable) {
    Preconditions.checkNotNull(executable, EXECUTABLE_NULL_MESSAGE);

    final String message = "Program %s is not installed or not on the PATH!".formatted(executable);
    super(message);
  }

  /**
   * Creates an exception for a program that is not installed and that MCAV cannot install on this platform.
   *
   * @param executable the name of the missing program
   * @param platform   the platform that MCAV has no packages of the program for
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public ExecutableNotInPathException(final String executable, final Platform platform) {
    Preconditions.checkNotNull(executable, EXECUTABLE_NULL_MESSAGE);
    Preconditions.checkNotNull(platform, "Platform must not be null!");

    final Os os = platform.getOs();
    final Arch architecture = platform.getArch();
    final String message = "Program %s is not installed or not on the PATH, and on %s %s MCAV cannot install it!".formatted(
      executable,
      os,
      architecture
    );
    super(message);
  }
}
