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
package me.brandonli.mcav.installer;

/**
 * This exception is thrown to indicate a violation of integrity for a JAR entry.
 * It is used to signal that an expected condition related to the security
 * or integrity of a JAR entry has failed.
 * <p>
 * The exception extends {@link SecurityException}, inheriting its behavior while
 * providing a specific context for JAR entry validation issues.
 * <p>
 * Constructors provide mechanisms to supply detailed exception messages when the
 * issue is encountered.
 */
public class JarEntryIntegrityException extends SecurityException {

  private static final long serialVersionUID = -5481191042398056901L;

  JarEntryIntegrityException(final String message) {
    super(message);
  }
}
