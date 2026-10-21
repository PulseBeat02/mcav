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
import com.google.common.base.Preconditions;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Locale;

/**
 * Detects the operating system and CPU architecture of the running JVM.
 *
 * <p>The values describe the JVM, not the machine: a 64-bit x86 JVM running under emulation on an ARM machine is
 * reported as 64-bit x86, which is what matters for loading native libraries.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class OsUtils {

  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final String DATA_MODEL_PROPERTY = "sun.arch.data.model";

  private static final String MAC_NAME = "mac";
  private static final String DARWIN_NAME = "darwin";
  private static final String WINDOWS_NAME = "win";
  private static final String FREEBSD_NAME = "freebsd";
  private static final String LINUX_NAME = "linux";

  private static final String ARM_NAME = "arm";
  private static final String AARCH_NAME = "aarch";
  private static final String X86_NAME = "86";
  private static final String AMD64_NAME = "amd64";

  private static final String SIXTY_FOUR_BITS = "64";
  private static final String THIRTY_TWO_BITS = "32";

  private static final Os CURRENT_OS;
  private static final Arch CURRENT_ARCH;
  private static final Bits CURRENT_BITS;

  static {
    final String osName = getProperty(OS_NAME_PROPERTY);
    final String osArch = getProperty(OS_ARCH_PROPERTY);
    final String dataModel = getProperty(DATA_MODEL_PROPERTY);
    CURRENT_OS = detectOs(osName);
    CURRENT_ARCH = detectArch(osArch);
    CURRENT_BITS = detectBits(osArch, dataModel);
  }

  private OsUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  @VisibleForTesting
  static String getProperty(final String key) {
    final String value = System.getProperty(key);
    if (value == null) {
      return "";
    }

    return value.toLowerCase(Locale.ROOT);
  }

  static Os detectOs(final String osName) {
    // "darwin" contains "win", so macOS has to be recognized before Windows
    if (osName.contains(MAC_NAME) || osName.contains(DARWIN_NAME)) {
      return Os.MAC;
    }

    if (osName.contains(WINDOWS_NAME)) {
      return Os.WINDOWS;
    }

    if (osName.contains(FREEBSD_NAME)) {
      return Os.FREEBSD;
    }

    if (osName.contains(LINUX_NAME)) {
      return Os.LINUX;
    }

    return Os.OTHER;
  }

  static Arch detectArch(final String osArch) {
    final boolean isArm = osArch.contains(ARM_NAME) || osArch.contains(AARCH_NAME);
    if (isArm) {
      return Arch.ARM;
    }

    final boolean isX86 = osArch.contains(X86_NAME) || osArch.equals(AMD64_NAME);
    if (isX86) {
      return Arch.X86;
    }

    return Arch.OTHER;
  }

  static Bits detectBits(final String osArch, final String dataModel) {
    if (dataModel.equals(SIXTY_FOUR_BITS)) {
      return Bits.BITS_64;
    }

    if (dataModel.equals(THIRTY_TWO_BITS)) {
      return Bits.BITS_32;
    }

    final boolean isSixtyFourBit = osArch.contains(SIXTY_FOUR_BITS);
    if (isSixtyFourBit) {
      return Bits.BITS_64;
    }

    return Bits.BITS_32;
  }

  /**
   * Returns whether the dynamic linker of the system can load a shared library, such as {@code libasound.so.2}, the
   * way a native library that links it by name finds it. A library that is found stays loaded, as linking it would
   * load it.
   *
   * @param library the file name or path of the library
   * @return whether the library can be loaded
   * @throws NullPointerException if {@code library} is null
   * @since 1.0.0
   */
  @SuppressWarnings("restricted") // the lookup loads native code, which the JVM restricts
  public static boolean isLibraryAvailable(final String library) {
    Preconditions.checkNotNull(library, "Library must not be null!");

    try {
      final Arena arena = Arena.global();
      SymbolLookup.libraryLookup(library, arena);

      return true;
    } catch (final IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Returns the operating system of the JVM.
   *
   * @return the operating system
   * @since 1.0.0
   */
  public static Os getOs() {
    return CURRENT_OS;
  }

  /**
   * Returns the bitness of the JVM.
   *
   * @return 32-bit or 64-bit
   * @since 1.0.0
   */
  public static Bits getBits() {
    return CURRENT_BITS;
  }

  /**
   * Returns the CPU architecture family of the JVM.
   *
   * @return x86, ARM, or {@link Arch#OTHER} for processor families the installers do not know
   * @since 1.0.0
   */
  public static Arch getArch() {
    return CURRENT_ARCH;
  }
}
