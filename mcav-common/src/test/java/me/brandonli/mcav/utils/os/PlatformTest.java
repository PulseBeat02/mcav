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

import java.util.Objects;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class PlatformTest {

  @Test
  void shouldStoreItsParts() {
    final Platform platform = Platform.ofPlatform(Os.MAC, Arch.ARM, Bits.BITS_64);
    final Os os = platform.getOs();
    final Arch architecture = platform.getArch();
    final Bits bits = platform.getBits();
    assertEquals(Os.MAC, os);
    assertEquals(Arch.ARM, architecture);
    assertEquals(Bits.BITS_64, bits);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final Platform platform = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
    final Platform equalPlatform = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);
    final Platform otherOs = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    final Platform otherArch = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform otherBits = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_32);
    EqualityAssertions.assertEqualityContract(platform, equalPlatform, otherOs, otherArch, otherBits);
  }

  @Test
  void shouldHashTheSystemTheArchitectureAndTheBits() {
    final Platform platform = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

    final int hash = platform.hashCode();
    final int expected = Objects.hash(Os.LINUX, Arch.X86, Bits.BITS_64);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderEveryPart() {
    final Platform sixtyFour = Platform.ofPlatform(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    final Platform thirtyTwo = Platform.ofPlatform(Os.FREEBSD, Arch.ARM, Bits.BITS_32);
    final Platform unknown = Platform.ofPlatform(Os.OTHER, Arch.OTHER, Bits.BITS_64);
    final String sixtyFourText = sixtyFour.toString();
    final String thirtyTwoText = thirtyTwo.toString();
    final String unknownText = unknown.toString();
    assertEquals("Platform{os=WINDOWS, arch=X86, bits=BITS_64}", sixtyFourText);
    assertEquals("Platform{os=FREEBSD, arch=ARM, bits=BITS_32}", thirtyTwoText);
    assertEquals("Platform{os=OTHER, arch=OTHER, bits=BITS_64}", unknownText);
  }

  @Test
  void shouldKnowOnlyPlatformsWithAKnownOperatingSystemAndArchitecture() {
    final Platform known = Platform.ofPlatform(Os.LINUX, Arch.ARM, Bits.BITS_64);
    final Platform unknownOs = Platform.ofPlatform(Os.OTHER, Arch.X86, Bits.BITS_64);
    final Platform unknownArch = Platform.ofPlatform(Os.LINUX, Arch.OTHER, Bits.BITS_64);
    final boolean knownResult = known.isKnown();
    final boolean unknownOsResult = unknownOs.isKnown();
    final boolean unknownArchResult = unknownArch.isKnown();
    assertTrue(knownResult);
    assertFalse(unknownOsResult);
    assertFalse(unknownArchResult);
  }

  @Test
  void shouldDescribeTheRunningSystemLikeJna() {
    final Platform current = Platform.getCurrentPlatform();
    final Os os = current.getOs();
    final Arch architecture = current.getArch();
    final Bits bits = current.getBits();
    final boolean isWindows = com.sun.jna.Platform.isWindows(); // fqn: Platform is also a class of this package
    final boolean isMac = com.sun.jna.Platform.isMac(); // fqn: Platform is also a class of this package
    final boolean isIntel = com.sun.jna.Platform.isIntel(); // fqn: Platform is also a class of this package
    final boolean isArm = com.sun.jna.Platform.isARM(); // fqn: Platform is also a class of this package
    final boolean isSixtyFourBit = com.sun.jna.Platform.is64Bit(); // fqn: Platform is also a class of this package
    final boolean windows = os == Os.WINDOWS;
    assertEquals(isWindows, windows);

    final boolean mac = os == Os.MAC;
    assertEquals(isMac, mac);

    final boolean intel = architecture == Arch.X86;
    assertEquals(isIntel, intel);

    final boolean arm = architecture == Arch.ARM;
    assertEquals(isArm, arm);

    final boolean sixtyFourBit = bits == Bits.BITS_64;
    assertEquals(isSixtyFourBit, sixtyFourBit);
  }

  @Test
  void shouldRejectMissingParts() {
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(null, Arch.X86, Bits.BITS_64));
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(Os.LINUX, null, Bits.BITS_64));
    assertThrows(NullPointerException.class, () -> Platform.ofPlatform(Os.LINUX, Arch.X86, null));
  }
}
