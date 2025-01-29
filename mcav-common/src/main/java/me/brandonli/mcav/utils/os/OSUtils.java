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

import static java.util.Objects.requireNonNull;
import static me.brandonli.mcav.utils.os.Bits.BITS_32;
import static me.brandonli.mcav.utils.os.Bits.BITS_64;
import static me.brandonli.mcav.utils.os.OS.*;

import java.util.Locale;

/**
 * Utility class for operating system and architecture detection.
 * <p>
 * The OSUtils class provides static methods to identify the operating system,
 * bitness, and CPU architecture of the current runtime environment. It serves
 * as a utility for determining platform-specific properties that may affect
 * the behavior or compatibility of an application.
 * <p>
 * This class is final and cannot be instantiated.
 */
public final class OSUtils {

  private static final String OS_NAME = "os.name";
  private static final String PROCESSOR_ARCHITECTURE = "PROCESSOR_ARCHITECTURE";
  private static final String PROCESSOR_ARCHITEW6432 = "PROCESSOR_ARCHITEW6432";

  private static final String OS_ARCH;
  private static final OS CURRENT;
  private static final Bits BITS;
  private static final Arch ARM;

  static {
    OS_ARCH = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    CURRENT = getOperatingSystem0();
    BITS = is64Bits0();
    ARM = isArm0();
  }

  private OSUtils() {
    throw new UnsupportedOperationException();
  }

  private static OS getOperatingSystem0() {
    final String os = requireNonNull(System.getProperty(OS_NAME));
    final String lower = os.toLowerCase();
    if (lower.contains("win")) {
      return WINDOWS;
    } else if (lower.contains("mac")) {
      return MAC;
    } else if (lower.contains("freebsd")) {
      return FREEBSD;
    } else {
      return LINUX;
    }
  }

  private static Bits is64Bits0() {
    if (CURRENT == WINDOWS) {
      final String arch = System.getenv(PROCESSOR_ARCHITECTURE);
      final String wow64Arch = System.getenv(PROCESSOR_ARCHITEW6432);
      final boolean first = arch != null && arch.endsWith("64");
      final boolean second = wow64Arch != null && wow64Arch.endsWith("64");
      final boolean is64bit = first || second;
      return is64bit ? BITS_64 : BITS_32;
    } else {
      return OS_ARCH.contains("64") ? BITS_64 : BITS_32;
    }
  }

  private static Arch isArm0() {
    return OS_ARCH.contains("arm") ? Arch.ARM : Arch.X86;
  }

  /**
   * Retrieves the current operating system of the runtime environment.
   * <p>
   * This method determines the operating system based on the system properties
   * and returns the corresponding enumeration constant from the {@code OS} enumeration.
   *
   * @return the operating system as an {@code OS} enum value, which can be one of
   * {@code OS.WINDOWS}, {@code OS.MAC}, {@code OS.LINUX}, or {@code OS.FREEBSD}.
   */
  public static OS getOS() {
    return CURRENT;
  }

  /**
   * Retrieves the bitness of the system's CPU architecture.
   * <p>
   * The method returns the bitness of the current system's processor as determined
   * during the class initialization. The bitness can be either {@code BITS_32} for
   * a 32-bit system or {@code BITS_64} for a 64-bit system.
   *
   * @return the bitness of the CPU architecture, represented as a {@code Bits} enum value
   */
  public static Bits getBits() {
    return BITS;
  }

  /**
   * Retrieves the predefined CPU architecture of the current runtime environment.
   * <p>
   * This method determines the architecture (e.g., ARM or X86) based on the runtime
   * environment and system properties. The result is predetermined during the initialization
   * of the application and reflects the CPU architecture detected at that time.
   *
   * @return the CPU architecture of the current system as an {@link Arch} instance
   */
  public static Arch getArch() {
    return ARM;
  }
}
