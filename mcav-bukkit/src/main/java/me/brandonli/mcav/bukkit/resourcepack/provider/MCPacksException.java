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
package me.brandonli.mcav.bukkit.resourcepack.provider;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a custom exception specific to MC Packs, used for error handling
 * within the MC Packs functionality of a resource pack hosting system.
 * <p>
 * This exception is a subclass of {@link AssertionError}, allowing it to signal
 * assertion-like failures or conditions that are not expected to occur during
 * normal application execution. It is typically utilized to indicate problems
 * or violations within the context of MC Packs operations.
 * <p>
 * The exception includes a message that provides detailed information about
 * the nature of the error when it is instantiated.
 */
public class MCPacksException extends AssertionError {

  @Serial
  private static final long serialVersionUID = -6775463807604247034L;

  /**
   * Constructs an {@code MCPacksException} with the specified detail message.
   * This constructor allows the creation of an exception instance that can carry
   * additional context or description about the error, specific to MC Packs functionality.
   *
   * @param message the detail message explaining the reason for the exception,
   *                or {@code null} if no message is provided
   */
  public MCPacksException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs an {@code MCPacksException} with the specified detail message
   * and cause. This constructor allows the creation of an exception instance
   * that can carry additional context or description about the error, specific
   * to MC Packs functionality, along with the underlying cause of the exception.
   *
   * @param message the detail message explaining the reason for the exception,
   *                or {@code null} if no message is provided
   * @param cause   the cause of the exception, or {@code null} if no cause is provided
   */
  public MCPacksException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
