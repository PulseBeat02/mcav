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

import static com.sun.jna.Platform.is64Bit;
import static com.sun.jna.Platform.isARM;
import static com.sun.jna.Platform.isFreeBSD;
import static com.sun.jna.Platform.isIntel;
import static com.sun.jna.Platform.isLinux;
import static com.sun.jna.Platform.isMac;
import static com.sun.jna.Platform.isWindows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class OsUtilsTest {

  @ParameterizedTest
  @CsvSource({
    "windows 11, WINDOWS",
    "mac os x, MAC",
    "darwin, MAC",
    "freebsd, FREEBSD",
    "linux, LINUX",
    "sunos, OTHER",
    "solaris, OTHER",
    "openbsd, OTHER",
    "netbsd, OTHER",
    "aix, OTHER",
    "'', OTHER",
  })
  void shouldDetectTheOperatingSystem(final String osName, final Os expected) {
    final Os detected = OsUtils.detectOs(osName);
    assertEquals(expected, detected);
  }

  @ParameterizedTest
  @CsvSource({
    "aarch64, ARM",
    "arm, ARM",
    "arm64, ARM",
    "amd64, X86",
    "x86_64, X86",
    "x86, X86",
    "i386, X86",
    "i686, X86",
    "riscv64, OTHER",
    "ppc64le, OTHER",
    "ppc64, OTHER",
    "s390x, OTHER",
    "loongarch64, OTHER",
    "sparcv9, OTHER",
    "ia64, OTHER",
    "'', OTHER",
  })
  void detectsTheArchitecture(final String osArch, final Arch expected) {
    final Arch detected = OsUtils.detectArch(osArch);
    assertEquals(expected, detected);
  }

  @ParameterizedTest
  @CsvSource({ "x86, 64, BITS_64", "amd64, 32, BITS_32", "amd64, '', BITS_64", "aarch64, '', BITS_64", "x86, '', BITS_32" })
  void shouldDetectTheWordSizePreferringTheDataModel(final String osArch, final String dataModel, final Bits expected) {
    final Bits detected = OsUtils.detectBits(osArch, dataModel);
    assertEquals(expected, detected);
  }

  @Test
  void shouldAgreeWithJnaAboutTheRunningSystem() {
    final Os os = OsUtils.getOs();
    final Arch arch = OsUtils.getArch();
    final Bits bits = OsUtils.getBits();
    final boolean isWindows = isWindows();
    final boolean isMac = isMac();
    final boolean isLinux = isLinux();
    final boolean freeBsd = isFreeBSD();
    final boolean isIntel = isIntel();
    final boolean isArm = isARM();
    final boolean isSixtyFourBit = is64Bit();
    final boolean onWindows = os == Os.WINDOWS;
    assertEquals(isWindows, onWindows);

    final boolean onMac = os == Os.MAC;
    assertEquals(isMac, onMac);

    final boolean onLinux = os == Os.LINUX;
    assertEquals(isLinux, onLinux);

    final boolean onFreeBsd = os == Os.FREEBSD;
    assertEquals(freeBsd, onFreeBsd);

    final boolean onIntel = arch == Arch.X86;
    assertEquals(isIntel, onIntel);

    final boolean onArm = arch == Arch.ARM;
    assertEquals(isArm, onArm);

    final boolean onSixtyFourBit = bits == Bits.BITS_64;
    assertEquals(isSixtyFourBit, onSixtyFourBit);
  }

  @Test
  void shouldReadPropertiesInLowerCaseAndMissingOnesAsEmpty() {
    System.setProperty("mcav.test.property", "MiXeD");
    try {
      final String value = OsUtils.getProperty("mcav.test.property");
      assertEquals("mixed", value);
    } finally {
      System.clearProperty("mcav.test.property");
    }

    final String missing = OsUtils.getProperty("mcav.test.missing");
    assertEquals("", missing);
  }

  @Test
  void shouldFindALibraryTheDynamicLinkerCanLoad() throws IOException {
    final Path javaHome = Path.of(System.getProperty("java.home"));
    final String javaLibraryName = System.mapLibraryName("java");
    final Optional<Path> javaLibrary;
    try (final Stream<Path> files = Files.walk(javaHome)) {
      javaLibrary = files.filter(file -> javaLibraryName.equals(String.valueOf(file.getFileName()))).findFirst();
    }

    final Path library = javaLibrary.orElseThrow();
    final boolean available = OsUtils.isLibraryAvailable(library.toString());
    assertTrue(available, library.toString());
  }

  @Test
  void shouldNotFindALibraryTheSystemLacks() {
    final boolean available = OsUtils.isLibraryAvailable("libmcav-missing.so.0");
    assertFalse(available);
  }

  @Test
  void shouldRejectANullLibrary() {
    assertThrows(NullPointerException.class, () -> OsUtils.isLibraryAvailable(null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(OsUtils.class);
  }
}
