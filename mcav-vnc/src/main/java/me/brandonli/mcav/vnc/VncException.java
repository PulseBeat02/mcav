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
package me.brandonli.mcav.vnc;

import java.io.Serial;
import me.brandonli.mcav.MCAVException;

/**
 * Thrown when a VNC operation fails for a reason outside the caller's control, such as a server that cannot be
 * reached, refuses the handshake or rejects the credentials, or a key table that cannot be read.
 *
 * <p>Every failure of {@code mcav-vnc} that comes from the network or the machine is this type; catch it to handle
 * them all.
 *
 * @since 1.0.0
 */
public final class VncException extends MCAVException {

  @Serial
  private static final long serialVersionUID = 3581100892521623976L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public VncException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the cause
   * @since 1.0.0
   */
  public VncException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
