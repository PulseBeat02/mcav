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
 * Represents an exception thrown when there was an issue uploading to MCPacks.
 */
public class MCPacksException extends AssertionError {

  @Serial
  private static final long serialVersionUID = -6775463807604247034L;

  /**
   * Constructs an exception with the specified detail message.
   *
   * @param message the detail message explaining the reason for the exception,
   */
  public MCPacksException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs an exception with the specified detail message and cause.
   *
   * @param message the detail message explaining the reason for the exception,
   * @param cause   the cause of the exception, which can be null
   */
  public MCPacksException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
