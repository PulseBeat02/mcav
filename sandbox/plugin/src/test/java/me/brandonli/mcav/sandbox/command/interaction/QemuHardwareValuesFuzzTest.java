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

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the hardware values players type into {@code /mcav vm create}: whatever the text, it is either refused with the
 * {@link IllegalArgumentException} the command turns into a message, or accepted as a value that names no file — no
 * separator of a path and none of the properties through which QEMU reads or writes one. The first byte of an input
 * picks the option. The seeds are the values of the tests and the documentation.
 */
@Tag("fuzz")
final class QemuHardwareValuesFuzzTest {

  private static final List<String> OPTIONS = List.of("machine", "m", "smp", "accel", "boot", "name", "rtc", "cpu", "k", "vga");

  private static final List<String> FILE_PROPERTIES = List.of(
    "dumpdtb=",
    "dtb=",
    "kernel=",
    "initrd=",
    "firmware=",
    "splash=",
    "memory-backend=",
    "pcspk-audiodev="
  );

  @FuzzTest(maxDuration = "30s")
  void everyTypedValueNamesNoFileOrIsRefused(final byte[] input) {
    if (input.length == 0) {
      return;
    }
    final String option = OPTIONS.get((input[0] & 0xFF) % OPTIONS.size());
    final String value = new String(input, 1, input.length - 1, StandardCharsets.UTF_8);
    try {
      QemuHardwareValues.check(option, value);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    assertFalse(value.indexOf('/') >= 0 || value.indexOf('\\') >= 0, () -> "-" + option + " accepted a path: " + value);
    for (final String property : FILE_PROPERTIES) {
      assertFalse(value.contains(property), () -> "-" + option + " accepted " + property + " in " + value);
    }
  }
}
