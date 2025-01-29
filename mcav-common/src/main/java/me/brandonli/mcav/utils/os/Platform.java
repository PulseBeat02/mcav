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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents the platform details of the current runtime environment.
 * <p>
 * The Platform class encapsulates information about the operating system,
 * CPU architecture, and bitness (32-bit or 64-bit) of the system. It provides
 * methods to retrieve these details and utility to get the current platform
 * configuration.
 * <p>
 * This class is immutable and thread-safe.
 */
public final class Platform {

  private static final Platform CURRENT_PLATFORM;

  static {
    final OS os = OSUtils.getOS();
    final Arch arch = OSUtils.getArch();
    final Bits bits64 = OSUtils.getBits();
    CURRENT_PLATFORM = Platform.ofPlatform(os, arch, bits64);
  }

  private final OS os;
  private final Arch arch;
  private final Bits bits;

  Platform(final OS os, final Arch arch, final Bits bits) {
    this.os = os;
    this.arch = arch;
    this.bits = bits;
  }

  /**
   * Creates a new Platform.
   *
   * @param os   the operating system
   * @param arch the cpu architecture (arm or no arm)
   * @param bits the cpu architecture (32-bit or 64-bit)
   * @return new operating system specific
   */
  public static Platform ofPlatform(final OS os, final Arch arch, final Bits bits) {
    return new Platform(os, arch, bits);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(final @Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Platform)) {
      return false;
    }
    final Platform other = (Platform) obj;
    return this.os == other.os && this.arch == other.arch && this.bits == other.bits;
  }

  /**
   * Retrieves the operating system associated with this platform.
   * <p>
   * This method returns the operating system of the current platform
   * encapsulated within this object. The operating system is represented
   * as an instance of the {@code OS} enumeration, which includes constants
   * such as {@code WINDOWS}, {@code MAC}, {@code LINUX}, and {@code FREEBSD}.
   *
   * @return the operating system of the current platform
   */
  public OS getOS() {
    return this.os;
  }

  /**
   * Retrieves the CPU architecture of the current platform.
   * <p>
   * The architecture denotes the type of CPU architecture (e.g., x86 or ARM)
   * that the system is based on.
   *
   * @return the CPU architecture represented as an {@code Arch} enum value
   */
  public Arch getArch() {
    return this.arch;
  }

  /**
   * Retrieves the bitness of the system's CPU architecture.
   * <p>
   * The bitness indicates whether the system operates in a 32-bit or 64-bit mode.
   * This method returns the bitness value associated with the current platform.
   *
   * @return the {@code Bits} enumeration representing the system's bitness,
   * either {@code BITS_32} for 32-bit or {@code BITS_64} for 64-bit.
   */
  public Bits getBits() {
    return this.bits;
  }

  /**
   * Retrieves the current platform configuration of the runtime environment.
   * <p>
   * The current platform is determined based on the operating system, CPU architecture,
   * and bitness (32-bit or 64-bit) of the system. This method returns a singleton instance
   * representing the detected platform.
   *
   * @return the current platform instance representing the runtime environment's OS, architecture, and bitness
   */
  public static Platform getCurrentPlatform() {
    return CURRENT_PLATFORM;
  }
}
