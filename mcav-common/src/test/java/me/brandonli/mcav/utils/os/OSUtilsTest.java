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

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests {@link OSUtils}.
 */
final class OSUtilsTest {

  @ParameterizedTest
  @CsvSource(
    {
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
    }
  )
  void detectsTheOperatingSystem(final String osName, final OS expected) {
    final OS detected = OSUtils.detectOS(osName);
    assertEquals(expected, detected);
  }

  @ParameterizedTest
  @CsvSource(
    {
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
    }
  )
  void detectsTheArchitecture(final String osArch, final Arch expected) {
    final Arch detected = OSUtils.detectArch(osArch);
    assertEquals(expected, detected);
  }

  @ParameterizedTest
  @CsvSource({ "x86, 64, BITS_64", "amd64, 32, BITS_32", "amd64, '', BITS_64", "aarch64, '', BITS_64", "x86, '', BITS_32" })
  void detectsTheWordSizePreferringTheDataModel(final String osArch, final String dataModel, final Bits expected) {
    final Bits detected = OSUtils.detectBits(osArch, dataModel);
    assertEquals(expected, detected);
  }

  @Test
  void agreesWithJnaAboutTheRunningSystem() {
    // JNA detects the platform on its own to load its native library, so it is an independent witness
    final OS os = OSUtils.getOS();
    final Arch arch = OSUtils.getArch();
    final Bits bits = OSUtils.getBits();
    final boolean windows = isWindows();
    final boolean mac = isMac();
    final boolean linux = isLinux();
    final boolean freeBsd = isFreeBSD();
    final boolean intel = isIntel();
    final boolean arm = isARM();
    final boolean sixtyFourBit = is64Bit();
    assertEquals(windows, os == OS.WINDOWS);
    assertEquals(mac, os == OS.MAC);
    assertEquals(linux, os == OS.LINUX);
    assertEquals(freeBsd, os == OS.FREEBSD);
    assertEquals(intel, arch == Arch.X86);
    assertEquals(arm, arch == Arch.ARM);
    assertEquals(sixtyFourBit, bits == Bits.BITS_64);
  }

  @Test
  void readsPropertiesInLowerCaseAndMissingOnesAsEmpty() {
    System.setProperty("mcav.test.property", "MiXeD");
    try {
      final String value = OSUtils.getProperty("mcav.test.property");
      assertEquals("mixed", value);
    } finally {
      System.clearProperty("mcav.test.property");
    }
    final String missing = OSUtils.getProperty("mcav.test.missing");
    assertEquals("", missing);
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(OSUtils.class);
  }
}
