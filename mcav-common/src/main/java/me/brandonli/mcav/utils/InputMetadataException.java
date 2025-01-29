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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Exception class representing an error related to input metadata.
 * This exception is thrown when there is an assertion failure or
 * invalid input metadata encountered in the application.
 * <p>
 * Extends {@link AssertionError} to indicate a serious
 * problem that a reasonable application should not attempt to catch.
 */
public class InputMetadataException extends AssertionError {

  private static final long serialVersionUID = -2964304434518823943L;

  InputMetadataException(final @Nullable String message) {
    super(message);
  }
}
