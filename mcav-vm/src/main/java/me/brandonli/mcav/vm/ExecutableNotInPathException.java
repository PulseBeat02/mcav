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

import java.io.Serial;

/**
 * Exception thrown when an executable required by the library is not found in the system PATH.
 */
public class ExecutableNotInPathException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 2760594791392402124L;

  /**
   * Constructs a new exception with a detailed message indicating the missing executable.
   *
   * @param executable the name of the executable that is not found in the PATH
   */
  public ExecutableNotInPathException(final String executable) {
    super(
      "Executable %s is not in the system PATH. Please ensure it is installed and available in the PATH environment variable.".formatted(
          executable
        )
    );
  }
}
