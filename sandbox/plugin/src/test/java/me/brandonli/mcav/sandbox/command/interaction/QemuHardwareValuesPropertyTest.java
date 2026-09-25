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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertThrows;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of {@link QemuHardwareValues}: whatever a player types, a value that holds a path separator, or a
 * property QEMU uses to read or write a file, is refused for every hardware option.
 */
final class QemuHardwareValuesPropertyTest {

  private static final String SEED = "20260925";

  @Provide
  Arbitrary<String> options() {
    return Arbitraries.of("machine", "m", "smp", "accel", "boot", "name", "rtc", "cpu", "k", "vga");
  }

  @Provide
  Arbitrary<String> optionsWithProperties() {
    // a -cpu value may hold any feature, which names no file and changes no sound
    return Arbitraries.of("machine", "m", "smp", "accel", "boot", "name", "rtc", "k", "vga");
  }

  @Provide
  Arbitrary<String> fileProperties() {
    return Arbitraries.of("dumpdtb", "dtb", "kernel", "initrd", "firmware", "append", "splash", "memory-backend", "pcspk-audiodev");
  }

  @Property(seed = SEED, tries = 1000)
  void aValueWithAPathSeparatorIsRefused(
    @ForAll("options") final String option,
    @ForAll final String before,
    @ForAll final boolean backslash,
    @ForAll final String after
  ) {
    final String value = before + (backslash ? '\\' : '/') + after;
    assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check(option, value));
  }

  @Property(seed = SEED, tries = 1000)
  void aPropertyThatNamesAFileIsRefused(
    @ForAll("optionsWithProperties") final String option,
    @ForAll("fileProperties") final String property,
    @ForAll final String prefix
  ) {
    final String value = "pc," + property + "=" + prefix.replace(",", "") + "file";
    assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check(option, value));
  }
}
