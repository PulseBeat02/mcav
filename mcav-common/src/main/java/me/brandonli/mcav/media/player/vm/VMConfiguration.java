/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.player.vm;

import java.util.*;
import org.checkerframework.checker.nullness.qual.KeyFor;

/**
 * A builder class for creating QEMU virtual machine configurations.
 * Provides methods to configure various VM parameters like memory, CPU cores,
 * disk images, display options, and other QEMU settings.
 */
public class VMConfiguration {

  private final Map<String, String> options;

  private VMConfiguration() {
    this.options = new HashMap<>();
  }

  /**
   * Creates a new VMConfiguration builder.
   *
   * @return a new VMConfiguration builder instance
   */
  public static VMConfiguration builder() {
    return new VMConfiguration();
  }

  /**
   * Sets the amount of memory for the VM in megabytes.
   *
   * @param memoryMB the memory amount in megabytes
   * @return this builder instance
   */
  public VMConfiguration memory(final int memoryMB) {
    this.options.put("m", memoryMB + "M");
    return this;
  }

  /**
   * Sets the amount of memory with custom unit.
   *
   * @param amount the memory amount
   * @param unit   the unit (M for megabytes, G for gigabytes)
   * @return this builder instance
   */
  public VMConfiguration memory(final int amount, final String unit) {
    this.options.put("m", amount + unit);
    return this;
  }

  /**
   * Sets the number of CPU cores for the VM.
   *
   * @param cores the number of cores
   * @return this builder instance
   */
  public VMConfiguration cores(final int cores) {
    this.options.put("smp", String.valueOf(cores));
    return this;
  }

  /**
   * Sets the CD-ROM/ISO image path.
   *
   * @param isoPath the path to the ISO image
   * @return this builder instance
   */
  public VMConfiguration cdrom(final String isoPath) {
    this.options.put("cdrom", isoPath);
    return this;
  }

  /**
   * Sets the primary hard disk image.
   *
   * @param hdaPath the path to the hard disk image
   * @return this builder instance
   */
  public VMConfiguration hda(final String hdaPath) {
    this.options.put("hda", hdaPath);
    return this;
  }

  /**
   * Sets the secondary hard disk image.
   *
   * @param hdbPath the path to the hard disk image
   * @return this builder instance
   */
  public VMConfiguration hdb(final String hdbPath) {
    this.options.put("hdb", hdbPath);
    return this;
  }

  /**
   * Sets the boot order.
   *
   * @param bootOrder the boot order (e.g., "d" for CD-ROM, "c" for hard disk)
   * @return this builder instance
   */
  public VMConfiguration boot(final String bootOrder) {
    this.options.put("boot", bootOrder);
    return this;
  }

  /**
   * Sets a custom network configuration.
   *
   * @param netConfig the network configuration
   * @return this builder instance
   */
  public VMConfiguration network(final String netConfig) {
    this.options.put("net", netConfig);
    return this;
  }

  /**
   * Sets CPU model.
   *
   * @param model the CPU model name
   * @return this builder instance
   */
  public VMConfiguration cpu(final String model) {
    this.options.put("cpu", model);
    return this;
  }

  /**
   * Sets machine type.
   *
   * @param machineType the machine type
   * @return this builder instance
   */
  public VMConfiguration machine(final String machineType) {
    this.options.put("machine", machineType);
    return this;
  }

  /**
   * Enables or disables acceleration (KVM).
   *
   * @param enable true to enable, false to disable
   * @return this builder instance
   */
  public VMConfiguration kvm(final boolean enable) {
    if (enable) {
      this.options.put("enable-kvm", "");
    } else {
      this.options.remove("enable-kvm");
    }
    return this;
  }

  /**
   * Sets display type.
   *
   * @param displayType the display type (e.g., "sdl", "gtk", "none")
   * @return this builder instance
   */
  public VMConfiguration display(final String displayType) {
    this.options.put("display", displayType);
    return this;
  }

  /**
   * Configures graphics settings.
   *
   * @param enable true to enable graphics, false for headless mode
   * @return this builder instance
   */
  public VMConfiguration graphics(final boolean enable) {
    if (!enable) {
      this.options.put("nographic", "");
    } else {
      this.options.remove("nographic");
    }
    return this;
  }

  /**
   * Configures audio driver.
   *
   * @param driver the audio driver to use
   * @return this builder instance
   */
  public VMConfiguration audio(final String driver) {
    this.options.put("audio", driver);
    return this;
  }

  /**
   * Sets RAM size for a disk using integer value.
   *
   * @param size the size in megabytes
   * @return this builder instance
   */
  public VMConfiguration diskSize(final int size) {
    return this.diskSize(size + "M");
  }

  /**
   * Sets RAM size for a disk.
   *
   * @param size the size with unit (e.g., "1G", "500M")
   * @return this builder instance
   */
  public VMConfiguration diskSize(final String size) {
    this.options.put("drive", "file=fat:rw:" + size);
    return this;
  }

  /**
   * Adds a custom QEMU option.
   *
   * @param key   the option key
   * @param value the option value
   * @return this builder instance
   */
  public VMConfiguration option(final String key, final String value) {
    this.options.put(key, value);
    return this;
  }

  /**
   * Adds a flag option (option without value).
   *
   * @param flag the flag name
   * @return this builder instance
   */
  public VMConfiguration flag(final String flag) {
    this.options.put(flag, "");
    return this;
  }

  /**
   * Returns an unmodifiable view of the configuration options.
   *
   * @return the configured options
   */
  public Map<String, String> getOptions() {
    return Collections.unmodifiableMap(this.options);
  }

  /**
   * Builds the argument array for QEMU.
   *
   * @return a string array of QEMU arguments
   */
  public String[] buildArgs() {
    final List<String> args = new ArrayList<>();
    final Set<Map.Entry<@KeyFor("this.options") String, String>> entries = this.options.entrySet();
    for (final Map.Entry<String, String> entry : entries) {
      final String key = entry.getKey();
      final String value = entry.getValue();
      args.add("-" + key);
      if (!value.isEmpty()) {
        args.add(value);
      }
    }
    return args.toArray(new String[0]);
  }
}
