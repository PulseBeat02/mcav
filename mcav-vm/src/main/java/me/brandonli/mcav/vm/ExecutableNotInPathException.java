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

/**
 * Thrown when a program that must be installed on the machine, such as QEMU, is not on the {@code PATH} or in one of
 * the folders {@link ExecutableFinder} also searches.
 *
 * <p>It is a {@link RuntimeException}, so {@code catch (Exception)} handles it.
 */
public class ExecutableNotInPathException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 4163726305184327011L;

  /**
   * Constructs a new exception.
   *
   * @param executable the name of the missing program
   * @throws NullPointerException if the name is null
   */
  public ExecutableNotInPathException(final String executable) {
    Preconditions.checkNotNull(executable, "Executable must not be null");
    super("The program " + executable + " is not installed or not on the PATH");
  }
}
