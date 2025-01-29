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
 * Represents the CPU architecture of a system.
 * <p>
 * The Arch enumeration categorizes a system's processor architecture as either:
 * - X86: Denotes an x86-based architecture (e.g., Intel or AMD processors commonly found in desktops and laptops).
 * - ARM: Denotes an ARM-based architecture, generally used in mobile devices and some modern computing platforms.
 */
public enum Arch {
  /**
   * Represents the x86-based architecture of a system.
   * <p>
   * This constant denotes the x86 CPU architecture, commonly used in
   * Intel and AMD processors found in desktops, laptops, and general-purpose computing devices.
   */
  X86,
  /**
   * Denotes an ARM-based CPU architecture.
   * <p>
   * This architecture is commonly used in mobile devices, embedded systems,
   * and some modern computing platforms due to its power efficiency and
   * performance characteristics when compared to traditional x86 architectures.
   */
  ARM,
}
