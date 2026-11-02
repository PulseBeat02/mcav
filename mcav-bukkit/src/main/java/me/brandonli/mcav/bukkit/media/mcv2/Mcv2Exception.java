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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.io.Serial;
import me.brandonli.mcav.bukkit.BukkitException;

/**
 * Thrown when bytes are not a valid MCV2 frame, transport page or archive, when a frame cannot be decoded against
 * the reference it names, or when an encode fails for a reason outside the caller's control: its video cannot be
 * opened, read or released, its stream cannot be written, a job throws a checked exception, or its thread is
 * interrupted.
 *
 * <p>This is the only exception the MCV2 parsers and decoders throw for bad input. They treat every byte as hostile:
 * malformed non-null byte input is reported by this exception rather than an out-of-bounds read.
 * API preconditions such as non-null arguments and valid worker lifecycles throw the JDK's exceptions instead.
 *
 * @since 1.0.0
 */
public final class Mcv2Exception extends BukkitException {

  @Serial
  private static final long serialVersionUID = 4270539862718624891L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   * @since 1.0.0
   */
  public Mcv2Exception(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message the detail message
   * @param cause   the failure that caused this exception
   * @since 1.0.0
   */
  public Mcv2Exception(final String message, final Throwable cause) {
    super(message, cause);
  }
}
