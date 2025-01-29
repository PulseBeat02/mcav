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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An exception that represents an error occurring within the HTTP server.
 * <p>
 * This class is a subclass of {@link AssertionError} and can be used to signal
 * unexpected issues or states related to HTTP server operations.
 */
public class HttpServerException extends AssertionError {

  private static final long serialVersionUID = -6775463807604247034L;

  /**
   * Constructs a new {@code HttpServerException} with the specified detail message.
   *
   * @param message the detail message explaining the exception. This can be {@code null}.
   */
  public HttpServerException(final @Nullable String message) {
    super(message);
  }
}
