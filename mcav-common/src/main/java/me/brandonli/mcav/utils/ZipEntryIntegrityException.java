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
package me.brandonli.mcav.utils;

import java.io.Serial;

/**
 * Exception thrown to indicate that a ZIP entry integrity check has failed.
 * This exception extends {@code AssertionError}, signifying that the integrity
 * of a ZIP file entry could not be verified as expected.
 * <p>
 * It is used to signal critical errors encountered when dealing with ZIP file
 * entry operations, particularly when integrity constraints are violated.
 */
public class ZipEntryIntegrityException extends AssertionError {

  @Serial
  private static final long serialVersionUID = -949105092892572153L;

  ZipEntryIntegrityException(final String message) {
    super(message);
  }
}
