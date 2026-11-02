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
package me.brandonli.mcav.plugin;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when the plugin fails for a reason outside the caller's control, such as a file it cannot read or write,
 * the network, yt-dlp, Discord or Simple Voice Chat.
 *
 * <p>Every operational failure of {@code mcav-plugin} is this type or a subtype; catch it to handle them all. Misuse
 * and calls in the wrong state throw {@link IllegalArgumentException} or {@link IllegalStateException} instead.
 *
 * @since 1.0.0
 */
public class PluginException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 4958774597970139161L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public PluginException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the cause
   * @since 1.0.0
   */
  public PluginException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
