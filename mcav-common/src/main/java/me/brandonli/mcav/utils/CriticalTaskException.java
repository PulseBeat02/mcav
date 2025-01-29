/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents an exception that is thrown for critical task failures.
 * This exception indicates an assertion-like condition where a critical
 * error occurs during the execution of a task. It extends {@link AssertionError}.
 * <p>
 * Typically used to signal situations where the failure of a task is
 * considered unrecoverable or critical, requiring immediate attention.
 */
public class CriticalTaskException extends AssertionError {

  private static final long serialVersionUID = 2987450203603366888L;

  CriticalTaskException(final @Nullable String message) {
    super(message);
  }
}
