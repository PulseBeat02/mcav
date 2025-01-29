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
 * Represents the operating system of a system.
 * <p>
 * The OS enumeration provides constants for identifying the most
 * commonly used operating systems. This is useful in scenarios where
 * different functionality or compatibility is required based on the
 * underlying operating system.
 * <p>
 * The supported operating systems include:
 * - WINDOWS: Represents a Microsoft Windows-based system.
 * - MAC: Represents a macOS (Apple's desktop operating system) system.
 * - LINUX: Represents a Linux-based system.
 * - FREEBSD: Represents a system running on the FreeBSD operating system.
 */
public enum OS {
  /**
   * Represents a Microsoft Windows-based operating system.
   * <p>
   * This constant is used to indicate that the underlying system is running
   * Microsoft Windows. It is part of the {@code OS} enumeration and can be
   * utilized for determining platform-specific functionality or compatibility
   * requirements when the application is executed on a Windows environment.
   */
  WINDOWS,
  /**
   * Represents a macOS system.
   * <p>
   * This constant is used to identify systems running macOS, which is
   * Apple's desktop operating system. It is commonly utilized in scenarios
   * where specific behavior, compatibility, or functionality is required
   * for macOS environments.
   */
  MAC,
  /**
   * Represents a Linux-based operating system.
   * <p>
   * This constant denotes the Linux family of operating systems, typically
   * used in a variety of environments ranging from servers to personal computers,
   * and known for its open-source nature and flexibility.
   */
  LINUX,
  /**
   * Represents a system running the FreeBSD operating system.
   * <p>
   * FreeBSD is a free and open-source Unix-like operating system
   * that is based on the Berkeley Software Distribution (BSD).
   * It is widely used for servers, embedded systems, and networking
   * due to its performance, advanced security features, and
   * robust architecture.
   * <p>
   * This constant is part of the OS enumeration used to distinguish
   * FreeBSD-based systems from other operating systems.
   */
  FREEBSD,
}
