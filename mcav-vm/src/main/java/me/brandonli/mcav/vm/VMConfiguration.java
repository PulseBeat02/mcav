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
package me.brandonli.mcav.vm;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The QEMU command line of a virtual machine, without the display and VNC options which the player adds.
 *
 * <p>Options keep the order they were first set in, and setting an option again replaces its value. Options
 * QEMU accepts more than once, such as {@code -drive} or {@code -device}, are added with
 * {@link #repeatable(String, String)}.
 *
 * <p>The player adds the options it relies on unless the configuration sets them: {@code -vga std},
 * {@code -display none}, {@code -vnc} on the port of the settings, and a USB tablet ({@code -usb -device usb-tablet}),
 * which reports absolute pointer positions so clicks land where they are sent. The tablet is left out when the
 * configuration adds its own with {@code device("usb-tablet")} or uses the older {@code -usbdevice} option. To choose
 * the USB controller yourself, set the {@code usb} flag or {@code usb=on} in the machine type, and to run without USB
 * at all, which also leaves out the tablet, set {@code usb=off}, as in {@code machine("q35,usb=off")}.
 *
 * <pre><code>
 *   final VMConfiguration configuration = VMConfiguration.builder();
 *   configuration.cdrom("alpine.iso");
 *   configuration.memory(2048);
 *   configuration.cores(2);
 * </code></pre>
 */
public final class VMConfiguration {

  private final Map<String, String> options;
  private final List<String> repeatable;
  private final Set<String> flags;

  private VMConfiguration() {
    this.options = new LinkedHashMap<>();
    this.repeatable = new ArrayList<>();
    this.flags = new LinkedHashSet<>();
  }

  /**
   * Creates an empty configuration.
   *
   * @return the configuration
   */
  public static VMConfiguration builder() {
    return new VMConfiguration();
  }

  /**
   * Sets the memory of the machine ({@code -m}).
   *
   * @param memoryMB the memory in megabytes
   * @return this configuration
   */
  public VMConfiguration memory(final int memoryMB) {
    Preconditions.checkArgument(memoryMB > 0, "Memory must be positive but was %s", memoryMB);
    return this.option("m", memoryMB + "M");
  }

  /**
   * Sets the memory of the machine ({@code -m}) with a unit.
   *
   * @param amount the amount
   * @param unit   the unit, such as {@code M} or {@code G}
   * @return this configuration
   */
  public VMConfiguration memory(final int amount, final String unit) {
    Preconditions.checkArgument(amount > 0, "Memory must be positive but was %s", amount);
    Preconditions.checkNotNull(unit, "Unit must not be null");
    return this.option("m", amount + unit);
  }

  /**
   * Sets the number of virtual processors ({@code -smp}).
   *
   * @param cores the number of cores
   * @return this configuration
   */
  public VMConfiguration cores(final int cores) {
    Preconditions.checkArgument(cores > 0, "Cores must be positive but was %s", cores);
    final String value = String.valueOf(cores);
    return this.option("smp", value);
  }

  /**
   * Attaches an ISO image as the CD-ROM drive ({@code -cdrom}).
   *
   * @param isoPath the path of the image
   * @return this configuration
   */
  public VMConfiguration cdrom(final String isoPath) {
    Preconditions.checkNotNull(isoPath, "ISO path must not be null");
    return this.option("cdrom", isoPath);
  }

  /**
   * Attaches a disk image as the first hard disk ({@code -hda}).
   *
   * @param hdaPath the path of the image
   * @return this configuration
   */
  public VMConfiguration hda(final String hdaPath) {
    Preconditions.checkNotNull(hdaPath, "Disk path must not be null");
    return this.option("hda", hdaPath);
  }

  /**
   * Attaches a disk image as the second hard disk ({@code -hdb}).
   *
   * @param hdbPath the path of the image
   * @return this configuration
   */
  public VMConfiguration hdb(final String hdbPath) {
    Preconditions.checkNotNull(hdbPath, "Disk path must not be null");
    return this.option("hdb", hdbPath);
  }

  /**
   * Sets the boot order ({@code -boot}).
   *
   * @param bootOrder the boot order, such as {@code d} for the CD-ROM first
   * @return this configuration
   */
  public VMConfiguration boot(final String bootOrder) {
    Preconditions.checkNotNull(bootOrder, "Boot order must not be null");
    return this.option("boot", bootOrder);
  }

  /**
   * Sets the network configuration ({@code -nic}).
   *
   * @param networkConfiguration the configuration, such as {@code user,model=virtio-net-pci}
   * @return this configuration
   */
  public VMConfiguration network(final String networkConfiguration) {
    Preconditions.checkNotNull(networkConfiguration, "Network configuration must not be null");
    return this.option("nic", networkConfiguration);
  }

  /**
   * Sets the processor model ({@code -cpu}).
   *
   * @param model the model, such as {@code host} or {@code max}
   * @return this configuration
   */
  public VMConfiguration cpu(final String model) {
    Preconditions.checkNotNull(model, "CPU model must not be null");
    return this.option("cpu", model);
  }

  /**
   * Sets the machine type ({@code -machine}).
   *
   * @param machineType the type, such as {@code q35} or {@code virt}
   * @return this configuration
   */
  public VMConfiguration machine(final String machineType) {
    Preconditions.checkNotNull(machineType, "Machine type must not be null");
    return this.option("machine", machineType);
  }

  /**
   * Sets the accelerator ({@code -accel}). Without it the player picks the fastest accelerator available on the
   * machine and falls back to software emulation.
   *
   * @param accelerator the accelerator, such as {@code kvm}, {@code whpx}, {@code hvf}, or {@code tcg}
   * @return this configuration
   */
  public VMConfiguration accelerator(final String accelerator) {
    Preconditions.checkNotNull(accelerator, "Accelerator must not be null");
    return this.option("accel", accelerator);
  }

  /**
   * Sets the graphics adapter ({@code -vga}).
   *
   * @param adapter the adapter, such as {@code std} or {@code virtio}
   * @return this configuration
   */
  public VMConfiguration vga(final String adapter) {
    Preconditions.checkNotNull(adapter, "Adapter must not be null");
    return this.option("vga", adapter);
  }

  /**
   * Sets the audio backend ({@code -audio}).
   *
   * @param driver the backend, such as {@code none}
   * @return this configuration
   */
  public VMConfiguration audio(final String driver) {
    Preconditions.checkNotNull(driver, "Driver must not be null");
    return this.option("audio", driver);
  }

  /**
   * Adds a drive ({@code -drive}). Drives can be added more than once.
   *
   * @param drive the drive specification, such as {@code file=disk.qcow2,format=qcow2}
   * @return this configuration
   */
  public VMConfiguration drive(final String drive) {
    Preconditions.checkNotNull(drive, "Drive must not be null");
    return this.repeatable("drive", drive);
  }

  /**
   * Adds a device ({@code -device}). Devices can be added more than once.
   *
   * @param device the device specification, such as {@code usb-tablet}
   * @return this configuration
   */
  public VMConfiguration device(final String device) {
    Preconditions.checkNotNull(device, "Device must not be null");
    return this.repeatable("device", device);
  }

  /**
   * Sets an option that takes a value, replacing an earlier value of the same option.
   *
   * @param key   the option name without the leading dash
   * @param value the value
   * @return this configuration
   */
  public VMConfiguration option(final String key, final String value) {
    Preconditions.checkNotNull(key, "Key must not be null");
    Preconditions.checkNotNull(value, "Value must not be null");
    Preconditions.checkArgument(!key.isBlank(), "Key must not be blank");
    this.options.put(key, value);
    return this;
  }

  /**
   * Adds an option that takes a value and may appear more than once.
   *
   * @param key   the option name without the leading dash
   * @param value the value
   * @return this configuration
   */
  public VMConfiguration repeatable(final String key, final String value) {
    Preconditions.checkNotNull(key, "Key must not be null");
    Preconditions.checkNotNull(value, "Value must not be null");
    Preconditions.checkArgument(!key.isBlank(), "Key must not be blank");
    this.repeatable.add("-" + key);
    this.repeatable.add(value);
    return this;
  }

  /**
   * Adds an option without a value, such as {@code enable-kvm}.
   *
   * @param flag the option name without the leading dash
   * @return this configuration
   */
  public VMConfiguration flag(final String flag) {
    Preconditions.checkNotNull(flag, "Flag must not be null");
    Preconditions.checkArgument(!flag.isBlank(), "Flag must not be blank");
    this.flags.add(flag);
    return this;
  }

  /**
   * Removes an option, every value of a repeatable option, or a flag.
   *
   * @param key the option name without the leading dash
   * @return this configuration
   */
  public VMConfiguration remove(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    this.options.remove(key);
    this.flags.remove(key);

    final String argument = "-" + key;
    final List<String> kept = new ArrayList<>();
    for (int index = 0; index < this.repeatable.size(); index += 2) {
      final String name = this.repeatable.get(index);
      final String value = this.repeatable.get(index + 1);
      if (!name.equals(argument)) {
        kept.add(name);
        kept.add(value);
      }
    }

    this.repeatable.clear();
    this.repeatable.addAll(kept);
    return this;
  }

  /**
   * Checks whether an option, a repeatable option, or a flag is set.
   *
   * @param key the option name without the leading dash
   * @return true if set
   */
  public boolean has(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    final List<String> values = this.getAll(key);
    return this.options.containsKey(key) || this.flags.contains(key) || !values.isEmpty();
  }

  /**
   * Gets the values of a repeatable option, such as every {@code -device}.
   *
   * @param key the option name without the leading dash
   * @return the values in the order they were added, which cannot be modified
   */
  public List<String> getAll(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    final String argument = "-" + key;
    final List<String> values = new ArrayList<>();
    for (int index = 0; index < this.repeatable.size(); index += 2) {
      final String name = this.repeatable.get(index);
      if (name.equals(argument)) {
        final String value = this.repeatable.get(index + 1);
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  /**
   * Gets the value of an option. Repeatable options are read with {@link #getAll(String)}.
   *
   * @param key the option name without the leading dash
   * @return the value, or null if the option is not set
   */
  public @Nullable String get(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return this.options.get(key);
  }

  /**
   * Gets the command-line arguments in order: options, repeatable options, then flags.
   *
   * @return the arguments, which cannot be modified
   */
  public List<String> getArguments() {
    final List<String> arguments = new ArrayList<>();
    // every option has a value, options without one are flags
    for (final Map.Entry<String, String> entry : this.options.entrySet()) {
      final String key = entry.getKey();
      final String value = entry.getValue();
      arguments.add("-" + key);
      arguments.add(value);
    }

    arguments.addAll(this.repeatable);

    for (final String flag : this.flags) {
      arguments.add("-" + flag);
    }
    return List.copyOf(arguments);
  }

  /**
   * Gets the command-line arguments as an array.
   *
   * @return the arguments
   */
  public String[] buildArgs() {
    final List<String> arguments = this.getArguments();
    return arguments.toArray(new String[0]);
  }

  @Override
  public String toString() {
    final List<String> arguments = this.getArguments();
    return String.join(" ", arguments);
  }
}
