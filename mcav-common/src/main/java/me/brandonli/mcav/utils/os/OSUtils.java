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

import com.google.common.annotations.VisibleForTesting;
import java.util.Locale;

/**
 * Detects the operating system and CPU architecture of the running JVM.
 *
 * <p>The values describe the JVM, not the machine: a 64-bit x86 JVM running under emulation on an ARM machine is
 * reported as 64-bit x86, which is what matters for loading native libraries.
 */
public final class OSUtils {

  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final String DATA_MODEL_PROPERTY = "sun.arch.data.model";

  private static final OS CURRENT_OS;
  private static final Arch CURRENT_ARCH;
  private static final Bits CURRENT_BITS;

  static {
    final String osName = getProperty(OS_NAME_PROPERTY);
    final String osArch = getProperty(OS_ARCH_PROPERTY);
    final String dataModel = getProperty(DATA_MODEL_PROPERTY);
    CURRENT_OS = detectOS(osName);
    CURRENT_ARCH = detectArch(osArch);
    CURRENT_BITS = detectBits(osArch, dataModel);
  }

  private OSUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  @VisibleForTesting
  static String getProperty(final String key) {
    final String value = System.getProperty(key);
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT);
  }

  static OS detectOS(final String osName) {
    // "darwin" contains "win", so macOS has to be recognized before Windows
    if (osName.contains("mac") || osName.contains("darwin")) {
      return OS.MAC;
    }
    if (osName.contains("win")) {
      return OS.WINDOWS;
    }
    if (osName.contains("freebsd")) {
      return OS.FREEBSD;
    }
    if (osName.contains("linux")) {
      return OS.LINUX;
    }
    // OpenBSD, NetBSD, Solaris, AIX and the like: Linux binaries do not run there
    return OS.OTHER;
  }

  static Arch detectArch(final String osArch) {
    final boolean arm = osArch.contains("arm") || osArch.contains("aarch");
    if (arm) {
      return Arch.ARM;
    }
    // x86, i386 to i686, x86_64 and amd64
    final boolean x86 = osArch.contains("86") || osArch.equals("amd64");
    if (x86) {
      return Arch.X86;
    }
    // riscv64, ppc64le, s390x, loongarch64 and the like: x86 binaries do not run there
    return Arch.OTHER;
  }

  static Bits detectBits(final String osArch, final String dataModel) {
    if (dataModel.equals("64")) {
      return Bits.BITS_64;
    }
    if (dataModel.equals("32")) {
      return Bits.BITS_32;
    }
    final boolean sixtyFour = osArch.contains("64");
    if (sixtyFour) {
      return Bits.BITS_64;
    }
    return Bits.BITS_32;
  }

  /**
   * Gets the operating system of the JVM.
   *
   * @return the operating system
   */
  public static OS getOS() {
    return CURRENT_OS;
  }

  /**
   * Gets the bitness of the JVM.
   *
   * @return 32-bit or 64-bit
   */
  public static Bits getBits() {
    return CURRENT_BITS;
  }

  /**
   * Gets the CPU architecture family of the JVM.
   *
   * @return x86, ARM, or {@link Arch#OTHER} for processor families the installers do not know
   */
  public static Arch getArch() {
    return CURRENT_ARCH;
  }
}
