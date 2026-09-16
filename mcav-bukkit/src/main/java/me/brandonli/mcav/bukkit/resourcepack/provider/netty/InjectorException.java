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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when a resource pack cannot be served on the Minecraft port, for example because the pack file does not
 * exist.
 *
 * <p>This is an {@link IllegalStateException}: the hosting was started while its pack file is missing, which is a
 * state of the server that the caller can fix, for example by writing the pack before starting the hosting again. It
 * is unchecked and never an {@link Error}, so a plugin can catch it together with its other startup failures and
 * disable itself cleanly.
 */
public class InjectorException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = 180052351920535948L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which names the pack that cannot be served
   */
  InjectorException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which names the pack that cannot be served
   * @param cause   the failure that caused this exception, or null if it is unknown
   */
  InjectorException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
