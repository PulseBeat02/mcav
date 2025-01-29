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
package me.brandonli.mcav;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents an exception that indicates a failure during the loading process in the MCAV library.
 * <p>
 * This exception is typically thrown to signal that a critical error has occurred
 * while attempting to load or initialize a resource or dependency within the MCAV framework.
 * It extends {@code AssertionError} to provide additional context related to failed assertions
 * during the loading phase.
 */
public class MCAVLoadingException extends AssertionError {

  @Serial
  private static final long serialVersionUID = 5512824472714665790L;

  MCAVLoadingException(final @Nullable String message) {
    super(message);
  }
}
