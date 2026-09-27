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

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The values the hardware options of {@code /mcav vm create} may have: every comma-separated part of a value must have
 * one of the forms listed for its option, and everything else is refused.
 *
 * <p>QEMU's options take properties that name files: {@code -machine dumpdtb=} writes the device tree into a file and
 * {@code firmware=}, {@code kernel=}, {@code initrd=} and {@code dtb=} read one into the guest, relative to the
 * working folder of the server; {@code -boot splash=} reads a picture, and {@code -machine pcspk-audiodev=} would route
 * the sound of the guest, which mcav owns. A check for path separators lets all of them through, so the values are
 * checked against a positive list instead: machine types, accelerators, sizes, counts, CPU models and features, and
 * the switches of each option. The parts of {@code -drive} besides its disk image are checked the same way: only the
 * properties that say how the drive is attached, cached and reported are allowed, none that opens another file or
 * block node.
 */
final class QemuHardwareValues {

  private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");
  private static final Pattern SIZE = Pattern.compile("\\d{1,9}[KkMmGgTt]?");
  private static final Pattern SWITCH = Pattern.compile("on|off");
  private static final Pattern SWITCH_OR_AUTO = Pattern.compile("on|off|auto");
  private static final Pattern BOOT_DEVICES = Pattern.compile("[a-p]{1,16}");
  private static final Pattern GUEST_NAME = Pattern.compile("[A-Za-z0-9 ._-]{0,64}");

  // properties of -machine that change the emulated hardware and never name a file
  private static final Map<String, Pattern> MACHINE_PROPERTIES = Map.ofEntries(
    Map.entry("type", WORD),
    Map.entry("accel", Pattern.compile("[a-z]{3,4}(:[a-z]{3,4}){0,3}")),
    Map.entry("usb", SWITCH),
    Map.entry("vmport", SWITCH_OR_AUTO),
    Map.entry("smm", SWITCH_OR_AUTO),
    Map.entry("kernel-irqchip", Pattern.compile("on|off|split")),
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
  private static final Map<String, Pattern> SMP_PROPERTIES = Map.of(
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
  private static final Map<String, Pattern> ACCEL_PROPERTIES = Map.of(
    "thread",
    Pattern.compile("single|multi"),
    "tb-size",
    NUMBER,
    "split-wx",
    SWITCH,
    "one-insn-per-tb",
    SWITCH,
    "kernel-irqchip",
    Pattern.compile("on|off|split"),
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
  private static final Map<String, Pattern> RTC_PROPERTIES = Map.of(
    "base",
    Pattern.compile("utc|localtime|\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}:\\d{2})?"),
    "clock",
    Pattern.compile("host|rt|vm"),
    "driftfix",
    Pattern.compile("none|slew")
  );
  private static final Pattern CPU_FEATURE = Pattern.compile("[+-][A-Za-z0-9._-]{1,64}|[A-Za-z0-9._-]{1,64}=[A-Za-z0-9._-]{1,64}");
  // the properties through which QEMU reads or writes a file or routes sound; no CPU has them, and none passes as one
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
  // the properties of -drive besides file=, whose image the command resolves in the image folder: how the drive is
  // attached, cached and reported; everything else, such as another driver or a property of another node, is refused
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
  private static final Pattern KEYBOARD = Pattern.compile("[a-z]{2}(-[a-z]{2,3})?");
  private static final Pattern VGA = Pattern.compile("[a-z0-9]{2,16}");
  private static final Splitter PARTS = Splitter.on(',');
  private static final Splitter PROPERTY = Splitter.on('=').limit(2);

  private QemuHardwareValues() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks the value of a hardware option.
   *
   * @param name  the option, without its dash
   * @param value the value as entered
   * @throws IllegalArgumentException if a part of the value has no form the option allows
   */
  static void check(final String name, final String value) {
    final List<String> parts = PARTS.splitToList(value);
    final String first = parts.getFirst();
    final List<String> rest = parts.subList(1, parts.size());
    switch (name) {
      case "machine" -> checkFirstAndProperties(name, value, first, WORD, rest, MACHINE_PROPERTIES);
      case "m" -> checkFirstAndProperties(name, value, first, SIZE, rest, MEMORY_PROPERTIES);
      case "smp" -> checkFirstAndProperties(name, value, first, NUMBER, rest, SMP_PROPERTIES);
      case "accel" -> checkFirstAndProperties(name, value, first, Pattern.compile("[a-z]{3,4}"), rest, ACCEL_PROPERTIES);
      case "boot" -> checkFirstAndProperties(name, value, first, BOOT_DEVICES, rest, BOOT_PROPERTIES);
      case "name" -> checkFirstAndProperties(name, value, first, GUEST_NAME, rest, NAME_PROPERTIES);
      case "rtc" -> checkProperties(name, value, parts, RTC_PROPERTIES);
      case "cpu" -> checkCpu(value, first, rest);
      case "k" -> require(name, value, parts.size() == 1 && KEYBOARD.matcher(value).matches());
      case "vga" -> require(name, value, parts.size() == 1 && VGA.matcher(value).matches());
      default -> throw new IllegalArgumentException("The QEMU option -" + name + " is not a hardware option");
    }
  }

  /**
   * Gets the memory an {@code -m} value gives the machine, as QEMU reads it: a number of MiB, or of the unit its suffix
   * names, as the value itself or as its {@code size} property.
   *
   * @param value an {@code -m} value that {@link #check} accepted
   * @return the size in bytes, {@link Long#MAX_VALUE} for a size beyond it, or QEMU's default of 128 MiB for a value
   *         that sets no size
   */
  static long memoryBytes(final String value) {
    String size = "128";
    for (final String part : PARTS.split(value)) {
      if (!part.contains("=")) {
        size = part;
      } else if (part.startsWith("size=")) {
        size = part.substring("size=".length());
      }
    }
    final char last = size.charAt(size.length() - 1);
    final boolean suffix = Character.isLetter(last);
    final long number = Long.parseLong(suffix ? size.substring(0, size.length() - 1) : size);
    final int shift =
      switch (Character.toUpperCase(last)) {
        case 'K' -> 10;
        case 'G' -> 30;
        case 'T' -> 40;
        default -> 20;
      };
    // a number of at most nine digits shifted by at most 40 bits fits a long unless it is beyond 2^63 bytes
    return number > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : number << shift;
  }

  /**
   * Checks a part of a {@code -drive} value other than its disk image.
   *
   * @param part  the part, such as {@code media=disk}
   * @param value the whole value as entered, for the message
   * @throws IllegalArgumentException if the part is not a property of the list, or its value has another form
   */
  static void checkDriveProperty(final String part, final String value) {
    checkProperties("drive", value, List.of(part), DRIVE_PROPERTIES);
  }

  /**
   * Checks a value whose first part is either a plain value of a form or a property, followed by properties.
   */
  private static void checkFirstAndProperties(
    final String name,
    final String value,
    final String first,
    final Pattern plain,
    final List<String> rest,
    final Map<String, Pattern> properties
  ) {
    if (!first.contains("=")) {
      require(name, value, plain.matcher(first).matches());
      checkProperties(name, value, rest, properties);
      return;
    }
    checkProperties(name, value, PARTS.splitToList(value), properties);
  }

  private static void checkProperties(
    final String name,
    final String value,
    final List<String> parts,
    final Map<String, Pattern> properties
  ) {
    for (final String part : parts) {
      // every part is a property with a value, named in the list of the option
      final List<String> nameAndValue = PROPERTY.splitToList(part);
      final Pattern form = nameAndValue.size() == 2 ? properties.get(nameAndValue.getFirst()) : null;
      if (form == null) {
        throw new IllegalArgumentException("The QEMU option -" + name + " does not allow " + part + " in " + value);
      }
      require(name, value, form.matcher(nameAndValue.getLast()).matches());
    }
  }

  private static void checkCpu(final String value, final String model, final List<String> features) {
    require("cpu", value, WORD.matcher(model).matches());
    for (final String feature : features) {
      require("cpu", value, CPU_FEATURE.matcher(feature).matches());
      // a switch such as +avx2 has no value; a property may not use the name of one that reaches a file
      if (feature.contains("=")) {
        final String property = PROPERTY.splitToList(feature).getFirst();
        require("cpu", value, !FILE_PROPERTIES.contains(property));
      }
    }
  }

  private static void require(final String name, final String value, final boolean allowed) {
    if (!allowed) {
      throw new IllegalArgumentException("The QEMU option -" + name + " does not allow the value " + value);
    }
  }
}
