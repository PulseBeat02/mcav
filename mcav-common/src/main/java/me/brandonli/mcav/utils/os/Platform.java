/*
 * This file is part of mcav, a media playback library for Java
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

import com.google.common.base.Preconditions;
import java.util.Locale;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A combination of operating system, CPU architecture family, and bitness, such as 64-bit x86 Windows. Platforms
 * identify which native build of a program a machine needs. Instances are immutable and compare by value.
 */
public final class Platform {

  private static final Platform CURRENT_PLATFORM = detectCurrentPlatform();

  private final OS os;
  private final Arch arch;
  private final Bits bits;

  Platform(final OS os, final Arch arch, final Bits bits) {
    Preconditions.checkNotNull(os, "OS must not be null");
    Preconditions.checkNotNull(arch, "Arch must not be null");
    Preconditions.checkNotNull(bits, "Bits must not be null");
    this.os = os;
    this.arch = arch;
    this.bits = bits;
  }

  private static Platform detectCurrentPlatform() {
    final OS os = OSUtils.getOS();
    final Arch arch = OSUtils.getArch();
    final Bits bits = OSUtils.getBits();
    return new Platform(os, arch, bits);
  }

  /**
   * Creates a platform.
   *
   * @param os   the operating system
   * @param arch the CPU architecture family
   * @param bits the bitness
   * @return the platform
   */
  public static Platform ofPlatform(final OS os, final Arch arch, final Bits bits) {
    return new Platform(os, arch, bits);
  }

  /**
   * Gets the platform of the running JVM.
   *
   * @return the current platform
   */
  public static Platform getCurrentPlatform() {
    return CURRENT_PLATFORM;
  }

  /**
   * Gets the operating system.
   *
   * @return the operating system
   */
  public OS getOS() {
    return this.os;
  }

  /**
   * Gets the CPU architecture family.
   *
   * @return the architecture
   */
  public Arch getArch() {
    return this.arch;
  }

  /**
   * Gets the bitness.
   *
   * @return 32-bit or 64-bit
   */
  public Bits getBits() {
    return this.bits;
  }

  /**
   * Checks whether both the operating system and the CPU architecture belong to a family the installers know.
   * Programs are never downloaded for an unknown platform, because no known build runs there.
   *
   * @return false if the operating system or the architecture is {@code OTHER}
   */
  public boolean isKnown() {
    return this.os != OS.OTHER && this.arch != Arch.OTHER;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final Platform platform)) {
      return false;
    }
    return this.os == platform.os && this.arch == platform.arch && this.bits == platform.bits;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.os, this.arch, this.bits);
  }

  /**
   * Describes the platform in a readable form, such as {@code windows-x86-64}.
   *
   * @return the description
   */
  @Override
  public String toString() {
    final String osName = this.os.name();
    final String lowerOsName = osName.toLowerCase(Locale.ROOT);
    final String archName = this.arch.name();
    final String lowerArchName = archName.toLowerCase(Locale.ROOT);
    final String bitsName = this.bits == Bits.BITS_64 ? "64" : "32";
    return lowerOsName + "-" + lowerArchName + "-" + bitsName;
  }
}
