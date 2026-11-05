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
package me.brandonli.mcav.plugin.command;

import java.io.Serial;
import me.brandonli.mcav.plugin.PluginException;

/**
 * Thrown when an image or video command cannot show the media it was given, for a reason the sender can act on, such
 * as a missing file, a file that is not an image, or a URL that does not answer.
 *
 * <p>The image and video commands send its message to the sender instead of logging a stack trace, so the message
 * names the media as the sender typed it and why it cannot be shown.
 *
 * @since 1.0.0
 */
public final class MediaUnavailableException extends PluginException {

  @Serial
  private static final long serialVersionUID = 2653671733213559943L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public MediaUnavailableException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the cause
   * @since 1.0.0
   */
  public MediaUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
