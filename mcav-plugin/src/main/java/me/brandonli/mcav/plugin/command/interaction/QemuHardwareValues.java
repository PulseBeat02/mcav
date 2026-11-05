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

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

// every comma-separated part of a value must match a form listed for its option
final class QemuHardwareValues {

  private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");
  private static final Pattern SIZE = Pattern.compile("\\d{1,9}[KkMmGgTt]?");
  private static final Pattern SWITCH = Pattern.compile("on|off");
  private static final Pattern SWITCH_OR_AUTO = Pattern.compile("on|off|auto");
  private static final Pattern SWITCH_OR_SPLIT = Pattern.compile("on|off|split");
  private static final Pattern BOOT_DEVICES = Pattern.compile("[a-p]{1,16}");
  private static final Pattern GUEST_NAME = Pattern.compile("[A-Za-z0-9 ._-]{0,64}");
  private static final Pattern ACCELERATOR = Pattern.compile("[a-z]{3,4}");
  private static final Pattern CPU_FEATURE = Pattern.compile("[+-][A-Za-z0-9._-]{1,64}|[A-Za-z0-9._-]{1,64}=[A-Za-z0-9._-]{1,64}");
  private static final Pattern KEYBOARD = Pattern.compile("[a-z]{2}(-[a-z]{2,3})?");
  private static final Pattern VGA = Pattern.compile("[a-z0-9]{2,16}");

  private static final Map<String, Pattern> MACHINE_PROPERTIES = Map.ofEntries(
    Map.entry("type", WORD),
    Map.entry("accel", Pattern.compile("[a-z]{3,4}(:[a-z]{3,4}){0,3}")),
    Map.entry("usb", SWITCH),
    Map.entry("vmport", SWITCH_OR_AUTO),
    Map.entry("smm", SWITCH_OR_AUTO),
    Map.entry("kernel-irqchip", SWITCH_OR_SPLIT),
    Map.entry("hpet", SWITCH),
    Map.entry("acpi", SWITCH_OR_AUTO),
    Map.entry("graphics", SWITCH),
    Map.entry("gic-version", Pattern.compile("2|3|4|host|max")),
    Map.entry("highmem", SWITCH),
    Map.entry("virtualization", SWITCH),
    Map.entry("secure", SWITCH),
    Map.entry("its", SWITCH),
    Map.entry("iommu", Pattern.compile("smmuv3|none")),
    Map.entry("aia", Pattern.compile("none|aplic|aplic-imsic")),
    Map.entry("mem-merge", SWITCH),
    Map.entry("dump-guest-core", SWITCH),
    Map.entry("sata", SWITCH),
    Map.entry("pic", SWITCH),
    Map.entry("pit", SWITCH),
    Map.entry("i8042", SWITCH)
  );
  private static final Map<String, Pattern> MEMORY_PROPERTIES = Map.of("size", SIZE, "slots", NUMBER, "maxmem", SIZE);
  private static final Map<String, Pattern> PROCESSORS_PROPERTIES = Map.of(
    "cpus",
    NUMBER,
    "maxcpus",
    NUMBER,
    "sockets",
    NUMBER,
    "dies",
    NUMBER,
    "clusters",
    NUMBER,
    "cores",
    NUMBER,
    "threads",
    NUMBER
  );
  private static final Map<String, Pattern> ACCELERATOR_PROPERTIES = Map.of(
    "thread",
    Pattern.compile("single|multi"),
    "tb-size",
    NUMBER,
    "split-wx",
    SWITCH,
    "one-insn-per-tb",
    SWITCH,
    "kernel-irqchip",
    SWITCH_OR_SPLIT,
    "dirty-ring-size",
    NUMBER
  );
  private static final Map<String, Pattern> BOOT_PROPERTIES = Map.of(
    "order",
    BOOT_DEVICES,
    "once",
    BOOT_DEVICES,
    "menu",
    SWITCH,
    "strict",
    SWITCH,
    "reboot-timeout",
    Pattern.compile("-?\\d{1,9}"),
    "splash-time",
    NUMBER
  );
  private static final Map<String, Pattern> NAME_PROPERTIES = Map.of("guest", GUEST_NAME, "process", WORD, "debug-threads", SWITCH);
  private static final Map<String, Pattern> CLOCK_PROPERTIES = Map.of(
    "base",
    Pattern.compile("utc|localtime|\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}:\\d{2})?"),
    "clock",
    Pattern.compile("host|rt|vm"),
    "driftfix",
    Pattern.compile("none|slew")
  );
  private static final Map<String, Pattern> DRIVE_PROPERTIES = Map.ofEntries(
    Map.entry("format", Pattern.compile("raw|qcow2|vmdk|vdi|vhdx|vpc")),
    Map.entry("if", Pattern.compile("ide|scsi|sd|floppy|pflash|virtio|none")),
    Map.entry("media", Pattern.compile("disk|cdrom")),
    Map.entry("index", NUMBER),
    Map.entry("bus", NUMBER),
    Map.entry("unit", NUMBER),
    Map.entry("id", WORD),
    Map.entry("serial", WORD),
    Map.entry("cache", Pattern.compile("none|writeback|writethrough|directsync|unsafe")),
    Map.entry("aio", Pattern.compile("threads|native|io_uring")),
    Map.entry("snapshot", SWITCH),
    Map.entry("readonly", SWITCH),
    Map.entry("copy-on-read", SWITCH),
    Map.entry("discard", Pattern.compile("ignore|unmap|off|on")),
    Map.entry("detect-zeroes", Pattern.compile("on|off|unmap")),
    Map.entry("werror", Pattern.compile("ignore|stop|report|enospc")),
    Map.entry("rerror", Pattern.compile("ignore|stop|report"))
  );

  private static final Set<String> FILE_PROPERTIES = Set.of(
    "dumpdtb",
    "dtb",
    "kernel",
    "initrd",
    "firmware",
    "append",
    "splash",
    "memory-backend",
    "pcspk-audiodev"
  );

  private static final Splitter PARTS = Splitter.on(',');

  private static final String PROPERTY_SEPARATOR = "=";

  private static final int NAME_AND_VALUE_PARTS = 2;

  private static final Splitter PROPERTY = Splitter.on(PROPERTY_SEPARATOR).limit(NAME_AND_VALUE_PARTS);

  private static final String MACHINE_OPTION = "machine";
  static final String MEMORY_OPTION = "m";
  private static final String PROCESSORS_OPTION = "smp";
  private static final String ACCELERATOR_OPTION = "accel";
  private static final String BOOT_OPTION = "boot";
  private static final String NAME_OPTION = "name";
  private static final String CLOCK_OPTION = "rtc";
  private static final String CPU_OPTION = "cpu";
  private static final String KEYBOARD_OPTION = "k";
  private static final String VGA_OPTION = "vga";
  static final String DRIVE_OPTION = "drive";

  static final Set<String> OPTIONS = Set.of(
    MACHINE_OPTION,
    MEMORY_OPTION,
    PROCESSORS_OPTION,
    ACCELERATOR_OPTION,
    BOOT_OPTION,
    NAME_OPTION,
    CLOCK_OPTION,
    CPU_OPTION,
    KEYBOARD_OPTION,
    VGA_OPTION
  );

  static final String DEFAULT_MEMORY_SIZE = "128";

  private static final String SIZE_PROPERTY = "size=";

  private static final char KIBIBYTE_SUFFIX = 'K';
  private static final char GIBIBYTE_SUFFIX = 'G';
  private static final char TEBIBYTE_SUFFIX = 'T';

  private static final int KIBIBYTE_SHIFT = 10;
  static final int MEBIBYTE_SHIFT = 20;
  private static final int GIBIBYTE_SHIFT = 30;
  private static final int TEBIBYTE_SHIFT = 40;

  private QemuHardwareValues() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void check(final String name, final String value) {
    final List<String> parts = PARTS.splitToList(value);
    final String first = parts.getFirst();
    final List<String> rest = parts.subList(1, parts.size());
    switch (name) {
      case MACHINE_OPTION -> checkFirstAndProperties(name, value, parts, WORD, MACHINE_PROPERTIES);
      case MEMORY_OPTION -> checkFirstAndProperties(name, value, parts, SIZE, MEMORY_PROPERTIES);
      case PROCESSORS_OPTION -> checkFirstAndProperties(name, value, parts, NUMBER, PROCESSORS_PROPERTIES);
      case ACCELERATOR_OPTION -> checkFirstAndProperties(name, value, parts, ACCELERATOR, ACCELERATOR_PROPERTIES);
      case BOOT_OPTION -> checkFirstAndProperties(name, value, parts, BOOT_DEVICES, BOOT_PROPERTIES);
      case NAME_OPTION -> checkFirstAndProperties(name, value, parts, GUEST_NAME, NAME_PROPERTIES);
      case CLOCK_OPTION -> checkProperties(name, value, parts, CLOCK_PROPERTIES);
      case CPU_OPTION -> checkCpu(value, first, rest);
      case KEYBOARD_OPTION -> {
        final boolean allowed = parts.size() == 1 && KEYBOARD.matcher(value).matches();
        require(name, value, allowed);
      }
      case VGA_OPTION -> {
        final boolean allowed = parts.size() == 1 && VGA.matcher(value).matches();
        require(name, value, allowed);
      }
      default -> {
        final String message = "QEMU option -%s is not a hardware option!".formatted(name);
        throw new IllegalArgumentException(message);
      }
    }
  }

  // as QEMU reads it: MiB without a suffix, 128 MiB when no size is set
  static long memoryBytes(final String value) {
    String size = DEFAULT_MEMORY_SIZE;
    for (final String part : PARTS.split(value)) {
      if (!part.contains(PROPERTY_SEPARATOR)) {
        size = part;
      } else if (part.startsWith(SIZE_PROPERTY)) {
        size = part.substring(SIZE_PROPERTY.length());
      }
    }
    final char last = size.charAt(size.length() - 1);
    final boolean suffixed = Character.isLetter(last);
    final int digitCount = suffixed ? size.length() - 1 : size.length();
    final String digits = size.substring(0, digitCount);
    final long number = Long.parseLong(digits);
    final char unit = Character.toUpperCase(last);
    final int shift = switch (unit) {
      case KIBIBYTE_SUFFIX -> KIBIBYTE_SHIFT;
      case GIBIBYTE_SUFFIX -> GIBIBYTE_SHIFT;
      case TEBIBYTE_SUFFIX -> TEBIBYTE_SHIFT;
      default -> MEBIBYTE_SHIFT;
    };
    final long largestShiftable = Long.MAX_VALUE >> shift;
    if (number > largestShiftable) {
      return Long.MAX_VALUE;
    }

    return number << shift;
  }

  static void checkDriveProperty(final String part, final String value) {
    checkProperties(DRIVE_OPTION, value, List.of(part), DRIVE_PROPERTIES);
  }

  private static void checkFirstAndProperties(
    final String name,
    final String value,
    final List<String> parts,
    final Pattern plain,
    final Map<String, Pattern> properties
  ) {
    final String first = parts.getFirst();
    final List<String> rest = parts.subList(1, parts.size());
    if (!first.contains(PROPERTY_SEPARATOR)) {
      final boolean allowed = plain.matcher(first).matches();
      require(name, value, allowed);
      checkProperties(name, value, rest, properties);
      return;
    }

    checkProperties(name, value, parts, properties);
  }

  private static void checkProperties(
    final String name,
    final String value,
    final List<String> parts,
    final Map<String, Pattern> properties
  ) {
    for (final String part : parts) {
      final List<String> nameAndValue = PROPERTY.splitToList(part);
      final boolean assigned = nameAndValue.size() == NAME_AND_VALUE_PARTS;
      final String propertyName = nameAndValue.getFirst();
      final @Nullable Pattern form = properties.get(propertyName);
      if (!assigned || form == null) {
        final String message = "Value %s of the QEMU option -%s contains %s, which is not allowed!".formatted(value, name, part);
        throw new IllegalArgumentException(message);
      }
      final boolean allowed = form.matcher(nameAndValue.getLast()).matches();
      require(name, value, allowed);
    }
  }

  private static void checkCpu(final String value, final String model, final List<String> features) {
    final boolean modelAllowed = WORD.matcher(model).matches();
    require(CPU_OPTION, value, modelAllowed);
    for (final String feature : features) {
      final boolean featureAllowed = CPU_FEATURE.matcher(feature).matches();
      require(CPU_OPTION, value, featureAllowed);
      if (feature.contains(PROPERTY_SEPARATOR)) {
        final List<String> nameAndValue = PROPERTY.splitToList(feature);
        final String property = nameAndValue.getFirst();
        final boolean allowed = !FILE_PROPERTIES.contains(property);
        require(CPU_OPTION, value, allowed);
      }
    }
  }

  private static void require(final String name, final String value, final boolean allowed) {
    if (!allowed) {
      final String message = "Value %s of the QEMU option -%s is not allowed!".formatted(value, name);
      throw new IllegalArgumentException(message);
    }
  }
}
