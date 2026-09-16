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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Platform}.
 */
final class PlatformTest {

  @Test
  void storesItsParts() {
    final Platform platform = Platform.ofPlatform(OS.MAC, Arch.ARM, Bits.BITS_64);
    final OS os = platform.getOS();
    final Arch arch = platform.getArch();
    final Bits bits = platform.getBits();
    assertEquals(OS.MAC, os);
    assertEquals(Arch.ARM, arch);
    assertEquals(Bits.BITS_64, bits);
  }

  @Test
  void followsTheEqualityContract() {
    final Platform platform = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
    final Platform equalPlatform = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_64);
    final Platform otherOs = Platform.ofPlatform(OS.WINDOWS, Arch.X86, Bits.BITS_64);
    final Platform otherArch = Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform otherBits = Platform.ofPlatform(OS.LINUX, Arch.X86, Bits.BITS_32);
    EqualityAssertions.assertEqualityContract(platform, equalPlatform, otherOs, otherArch, otherBits);
  }

  @Test
  void rendersAsLowerCaseTriple() {
    final Platform sixtyFour = Platform.ofPlatform(OS.WINDOWS, Arch.X86, Bits.BITS_64);
    final Platform thirtyTwo = Platform.ofPlatform(OS.FREEBSD, Arch.ARM, Bits.BITS_32);
    final Platform unknown = Platform.ofPlatform(OS.OTHER, Arch.OTHER, Bits.BITS_64);
    final String sixtyFourText = sixtyFour.toString();
    final String thirtyTwoText = thirtyTwo.toString();
    final String unknownText = unknown.toString();
    assertEquals("windows-x86-64", sixtyFourText);
    assertEquals("freebsd-arm-32", thirtyTwoText);
    assertEquals("other-other-64", unknownText);
  }

  @Test
  void knowsOnlyPlatformsWithAKnownOperatingSystemAndArchitecture() {
    final Platform known = Platform.ofPlatform(OS.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform unknownOs = Platform.ofPlatform(OS.OTHER, Arch.X86, Bits.BITS_64);
    final Platform unknownArch = Platform.ofPlatform(OS.LINUX, Arch.OTHER, Bits.BITS_64);
    final boolean knownResult = known.isKnown();
    final boolean unknownOsResult = unknownOs.isKnown();
    final boolean unknownArchResult = unknownArch.isKnown();
    assertTrue(knownResult);
    assertFalse(unknownOsResult);
    assertFalse(unknownArchResult);
  }

  @Test
  void describesTheRunningSystemLikeJna() {
    // JNA detects the platform on its own to load its native library, so it is an independent witness
    final Platform current = Platform.getCurrentPlatform();
    final OS os = current.getOS();
    final Arch arch = current.getArch();
    final Bits bits = current.getBits();
    final boolean windows = com.sun.jna.Platform.isWindows();
    final boolean mac = com.sun.jna.Platform.isMac();
    final boolean intel = com.sun.jna.Platform.isIntel();
    final boolean arm = com.sun.jna.Platform.isARM();
    final boolean sixtyFourBit = com.sun.jna.Platform.is64Bit();
    assertEquals(windows, os == OS.WINDOWS);
    assertEquals(mac, os == OS.MAC);
    assertEquals(intel, arch == Arch.X86);
    assertEquals(arm, arch == Arch.ARM);
    assertEquals(sixtyFourBit, bits == Bits.BITS_64);
  }

  @Test
  void rejectsMissingParts() {
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(null, Arch.X86, Bits.BITS_64));
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(OS.LINUX, null, Bits.BITS_64));
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(OS.LINUX, Arch.X86, null));
  }
}
