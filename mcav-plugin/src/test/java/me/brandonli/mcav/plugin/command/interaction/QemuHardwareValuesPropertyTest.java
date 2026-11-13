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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

final class QemuHardwareValuesPropertyTest {

  private static final String SEED = "20260925";

  private static final Set<String> DRIVE_PROPERTIES = Set.of(
    "format",
    "if",
    "media",
    "index",
    "bus",
    "unit",
    "id",
    "serial",
    "cache",
    "aio",
    "snapshot",
    "readonly",
    "copy-on-read",
    "discard",
    "detect-zeroes",
    "werror",
    "rerror"
  );

  private static final String PROPERTY_SEPARATOR = ",";
  private static final String VALUE_SEPARATOR = "=";

  private static final String MACHINE_OPTION = "machine";
  private static final String MEMORY_OPTION = "m";
  private static final String SMP_OPTION = "smp";
  private static final String ACCELERATOR_OPTION = "accel";
  private static final String BOOT_OPTION = "boot";
  private static final String NAME_OPTION = "name";
  private static final String RTC_OPTION = "rtc";
  private static final String CPU_OPTION = "cpu";
  private static final String KEYBOARD_OPTION = "k";
  private static final String VGA_OPTION = "vga";
  private static final List<String> OPTIONS = List.of(
    MACHINE_OPTION,
    MEMORY_OPTION,
    SMP_OPTION,
    ACCELERATOR_OPTION,
    BOOT_OPTION,
    NAME_OPTION,
    RTC_OPTION,
    CPU_OPTION,
    KEYBOARD_OPTION,
    VGA_OPTION
  );

  @Provide
  Arbitrary<String> options() {
    return Arbitraries.of(OPTIONS);
  }

  @Provide
  Arbitrary<String> fileProperties() {
    return Arbitraries.of("dumpdtb", "dtb", "kernel", "initrd", "firmware", "append", "splash", "memory-backend", "pcspk-audiodev");
  }

  @Property(seed = SEED, tries = 1000)
  void shouldRefuseAValueWithAPathSeparator(
    @ForAll("options") final String option,
    @ForAll final String before,
    @ForAll final boolean backslash,
    @ForAll final String after
  ) {
    final String value = before + (backslash ? '\\' : '/') + after;
    assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check(option, value));
  }

  @Property(seed = SEED, tries = 1000)
  void shouldRefuseAPropertyThatNamesAFile(
    @ForAll("options") final String option,
    @ForAll("fileProperties") final String property,
    @ForAll final String prefix
  ) {
    final String validBase = switch (option) {
      case MEMORY_OPTION -> "512";
      case SMP_OPTION -> "2";
      case ACCELERATOR_OPTION -> "tcg";
      case BOOT_OPTION -> "c";
      case NAME_OPTION -> "guest";
      case RTC_OPTION -> "base=utc";
      case CPU_OPTION -> "max";
      case KEYBOARD_OPTION -> "en-us";
      case VGA_OPTION -> "std";
      default -> "pc";
    };
    assertDoesNotThrow(() -> QemuHardwareValues.check(option, validBase));

    final String strippedPrefix = prefix.replace(PROPERTY_SEPARATOR, "");
    final String value = "%s%s%s%s%sfile".formatted(validBase, PROPERTY_SEPARATOR, property, VALUE_SEPARATOR, strippedPrefix);
    assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.check(option, value));
  }

  @Provide
  Arbitrary<String> unlistedDriveProperties() {
    return Arbitraries.strings()
      .withChars("abcdefghijklmnopqrstuvwxyz.-_")
      .ofMinLength(1)
      .ofMaxLength(24)
      .filter(name -> !DRIVE_PROPERTIES.contains(name));
  }

  @Property(seed = SEED, tries = 1000)
  void shouldRefuseADrivePropertyOutsideTheListWhateverItsValue(
    @ForAll("unlistedDriveProperties") final String property,
    @ForAll final String value
  ) {
    final String part = property + VALUE_SEPARATOR + value.replace(PROPERTY_SEPARATOR, "");
    assertThrows(IllegalArgumentException.class, () -> QemuHardwareValues.checkDriveProperty(part, "file=disk.img,%s".formatted(part)));
  }
}
