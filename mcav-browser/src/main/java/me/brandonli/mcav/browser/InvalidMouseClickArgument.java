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
package me.brandonli.mcav.browser;

import java.io.Serial;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Exception thrown to indicate that an invalid argument was provided for a mouse click operation.
 * This exception is a specific subclass of {@link IllegalArgumentException}, indicating that the
 * provided argument does not match the expected requirements for a mouse click event within the context
 * of operations involving the {@code MouseClick} type.
 * <p>
 * This is typically used in scenarios where mouse event arguments are verified and an illegal or
 * unsupported value is detected, such as arguments outside the predefined set of {@link MouseClick}
 * values or other custom-defined constraints.
 * <p>
 * Usage of this exception helps ensure robust validation of mouse event arguments and provides a clear
 * mechanism for signaling errors related to invalid inputs in mouse-related operations.
 */
public class InvalidMouseClickArgument extends IllegalArgumentException {

  @Serial
  private static final long serialVersionUID = -6679497293718318884L;

  public InvalidMouseClickArgument(final String message) {
    super(message);
  }
}
