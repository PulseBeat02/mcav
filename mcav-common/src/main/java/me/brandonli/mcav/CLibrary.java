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

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * JNA interface for the C library, specifically for the setenv function.
 * This allows setting environment variables from Java code.
 */
public interface CLibrary extends Library {
  /**
   * The singleton instance of the CLibrary.
   */
  CLibrary INSTANCE = Native.load("c", CLibrary.class);

  /**
   * Sets an environment variable.
   *
   * @param name The name of the environment variable.
   * @param value The value to set for the environment variable.
   * @param overwrite If non-zero, the variable will be overwritten if it already exists.
   * @return 0 on success, or -1 on error.
   */
  int setenv(String name, String value, int overwrite);
}
