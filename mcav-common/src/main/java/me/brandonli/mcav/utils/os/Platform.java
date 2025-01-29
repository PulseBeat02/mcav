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
    if (!(obj instanceof final Platform other)) {
      return false;
    }
    if (this.hashCode() != obj.hashCode()) {
      return false;
    }
    return this.os == other.os && this.arch == other.arch && this.bits == other.bits;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + (this.os != null ? this.os.hashCode() : 0);
    result = 31 * result + (this.arch != null ? this.arch.hashCode() : 0);
    result = 31 * result + (this.bits != null ? this.bits.hashCode() : 0);
    return result;
  }

  /**
   * Retrieves the operating system associated with this platform.
   */
  public OS getOS() {
    return this.os;
  }

  /**
   * Retrieves the CPU architecture of the current platform.
   */
  public Arch getArch() {
    return this.arch;
  }

  /**
   * Retrieves the bitness of the system's CPU architecture.
   */
  public Bits getBits() {
    return this.bits;
  }

  /**
   * Retrieves the current platform configuration of the runtime environment.
   *
   * @return the current platform instance representing the runtime environment's OS, architecture, and bitness
   */
  public static Platform getCurrentPlatform() {
    return CURRENT_PLATFORM;
  }
}
