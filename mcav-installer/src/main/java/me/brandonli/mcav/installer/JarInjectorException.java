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

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents an exception that occurs when jar injection fails or is unsupported.
 */
public class JarInjectorException extends UnsupportedOperationException {

  @Serial
  private static final long serialVersionUID = 861375458420366052L;

  JarInjectorException(final @Nullable String msg) {
    super(msg);
  }

  JarInjectorException(final @Nullable String msg, final @Nullable Throwable cause) {
    super(msg, cause);
  }
}
