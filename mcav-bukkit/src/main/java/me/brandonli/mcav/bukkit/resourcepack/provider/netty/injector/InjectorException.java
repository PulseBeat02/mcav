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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty.injector;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents an exception that indicates an error related to injector operations.
 * This exception is typically thrown when an issue occurs during the execution
 * of injector-related logic or functionality.
 */
public class InjectorException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 180052351920535948L;

  /**
   * Constructs a new InjectorException with the specified detail message.
   *
   * @param message the detail message that provides additional information about
   *                the exception.
   */
  public InjectorException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new InjectorException with the specified detail message and cause.
   *
   * @param message the detail message that provides additional information about
   *                the exception.
   * @param cause   the cause of the exception, which can be used to retrieve
   *                additional context about the error.
   */
  public InjectorException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
