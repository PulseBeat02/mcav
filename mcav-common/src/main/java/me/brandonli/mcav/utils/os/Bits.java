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
package me.brandonli.mcav.utils.os;

/**
 * Represents the bitness of a system's CPU architecture.
 * <p>
 * The Bits enumeration defines the possible bitness of a system, which can be:
 * - BITS_32: Indicates a 32-bit architecture.
 * - BITS_64: Indicates a 64-bit architecture.
 * <p>
 * This enum can be used to identify whether the system being executed on
 * is running a 32-bit or 64-bit CPU architecture. It can be useful for
 * making decisions about compatibility with certain software or hardware.
 */
public enum Bits {
  /**
   * Represents a 32-bit CPU architecture.
   * <p>
   * This constant is used to indicate that the system's processor operates in
   * a 32-bit mode. It can be utilized to determine compatibility with software
   * or hardware designed for 32-bit systems.
   */
  BITS_32,
  /**
   * Represents a 64-bit CPU architecture.
   * <p>
   * This constant specifies a 64-bit environment, commonly used in modern computing
   * systems to enable larger memory addressing and improved performance for software
   * designed for 64-bit platforms.
   * <p>
   * Applications can utilize this value to determine whether the running system is
   * 64-bit, aiding in compatibility decisions and optimizations.
   */
  BITS_64,
}
