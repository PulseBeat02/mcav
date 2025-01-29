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
 * Exception thrown to indicate errors during the loading of native libraries.
 * This class extends {@link AssertionError}, typically to signal fatal conditions
 * related to native resource handling that should not occur under normal circumstances.
 * <p>
 * Instances of this exception are generally used to report critical issues such as
 * failure to load mandatory native dependencies necessary for the application's functionality.
 */
public class NativeLoadingException extends AssertionError {

  private static final long serialVersionUID = -1718334825313639127L;

  NativeLoadingException(final @Nullable String msg) {
    super(msg);
  }

  NativeLoadingException(final @Nullable String msg, final @Nullable Throwable cause) {
    super(msg, cause);
  }
}
