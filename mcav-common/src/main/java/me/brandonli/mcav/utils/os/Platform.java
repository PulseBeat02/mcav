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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A combination of operating system, CPU architecture family, and bitness, such as 64-bit x86 Windows. Platforms
 * identify which native build of a program a machine needs. Instances are immutable and compare by value.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class Platform {

  private static final Platform CURRENT_PLATFORM = detectCurrentPlatform();

  private final Os os;
  private final Arch architecture;
  private final Bits bits;

  Platform(final Os os, final Arch architecture, final Bits bits) {
    Preconditions.checkNotNull(os, "OS must not be null!");
    Preconditions.checkNotNull(architecture, "Arch must not be null!");
    Preconditions.checkNotNull(bits, "Bits must not be null!");

    this.os = os;
    this.architecture = architecture;
    this.bits = bits;
  }

  /**
   * Creates a platform.
   *
   * @param os           the operating system
   * @param architecture the CPU architecture family
   * @param bits         the bitness
   * @return the platform
   * @throws NullPointerException if any platform component is null
   * @since 1.0.0
   */
  public static Platform ofPlatform(final Os os, final Arch architecture, final Bits bits) {
    return new Platform(os, architecture, bits);
  }

  /**
   * Returns the platform of the running JVM.
   *
   * @return the current platform
   * @since 1.0.0
   */
  public static Platform getCurrentPlatform() {
    return CURRENT_PLATFORM;
  }

  private static Platform detectCurrentPlatform() {
    final Os os = OsUtils.getOs();
    final Arch architecture = OsUtils.getArch();
    final Bits bits = OsUtils.getBits();
    return new Platform(os, architecture, bits);
  }

  /**
   * Returns whether both the operating system and the CPU architecture belong to a family the installers know.
   * Programs are never downloaded for an unknown platform, because no known build runs there.
   *
   * @return whether neither the operating system nor the architecture is {@code OTHER}
   * @since 1.0.0
   */
  public boolean isKnown() {
    return this.os != Os.OTHER && this.architecture != Arch.OTHER;
  }

  /**
   * Returns the operating system.
   *
   * @return the operating system
   * @since 1.0.0
   */
  public Os getOs() {
    return this.os;
  }

  /**
   * Returns the CPU architecture family.
   *
   * @return the architecture
   * @since 1.0.0
   */
  public Arch getArch() {
    return this.architecture;
  }

  /**
   * Returns the bitness.
   *
   * @return 32-bit or 64-bit
   * @since 1.0.0
   */
  public Bits getBits() {
    return this.bits;
  }

  /**
   * Returns whether {@code other} is a platform with the same operating system, architecture and bitness.
   *
   * @param other the object to compare with
   * @return whether both match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Platform platform)) {
      return false;
    }

    return this.os == platform.os && this.architecture == platform.architecture && this.bits == platform.bits;
  }

  /**
   * Returns a hash code built from the operating system, architecture and bitness.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.os, this.architecture, this.bits);
  }

  /**
   * Returns a debugging representation with the operating system, the architecture and the word size.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("os", this.os).add("arch", this.architecture).add("bits", this.bits).toString();
  }
}
