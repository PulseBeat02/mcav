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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A builder class for creating QEMU virtual machine configurations.
 */
public class VMConfiguration {

  private final List<String> arguments;

  private VMConfiguration() {
    this.arguments = new ArrayList<>();
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
    return this.option("m", memoryMB + "M");
  }

  /**
   * Sets the amount of memory with custom unit.
   *
   * @param amount the memory amount
   * @param unit   the unit (M for megabytes, G for gigabytes)
   * @return this builder instance
   */
  public VMConfiguration memory(final int amount, final String unit) {
    return this.option("m", amount + unit);
  }

  /**
   * Sets the number of CPU cores for the VM.
   *
   * @param cores the number of cores
   * @return this builder instance
   */
  public VMConfiguration cores(final int cores) {
    return this.option("smp", String.valueOf(cores));
  }

  /**
   * Sets the CD-ROM/ISO image path.
   *
   * @param isoPath the path to the ISO image
   * @return this builder instance
   */
  public VMConfiguration cdrom(final String isoPath) {
    return this.option("cdrom", isoPath);
  }

  /**
   * Sets the primary hard disk image.
   *
   * @param hdaPath the path to the hard disk image
   * @return this builder instance
   */
  public VMConfiguration hda(final String hdaPath) {
    return this.option("hda", hdaPath);
  }

  /**
   * Sets the secondary hard disk image.
   *
   * @param hdbPath the path to the hard disk image
   * @return this builder instance
   */
  public VMConfiguration hdb(final String hdbPath) {
    return this.option("hdb", hdbPath);
  }

  /**
   * Sets the boot order.
   *
   * @param bootOrder the boot order (e.g., "d" for CD-ROM, "c" for hard disk)
   * @return this builder instance
   */
  public VMConfiguration boot(final String bootOrder) {
    return this.option("boot", bootOrder);
  }

  /**
   * Sets a custom network configuration.
   *
   * @param netConfig the network configuration
   * @return this builder instance
   */
  public VMConfiguration network(final String netConfig) {
    return this.option("net", netConfig);
  }

  /**
   * Sets CPU model.
   *
   * @param model the CPU model name
   * @return this builder instance
   */
  public VMConfiguration cpu(final String model) {
    return this.option("cpu", model);
  }

  /**
   * Sets machine type.
   *
   * @param machineType the machine type
   * @return this builder instance
   */
  public VMConfiguration machine(final String machineType) {
    return this.option("machine", machineType);
  }

  /**
   * Enables or disables acceleration (KVM).
   *
   * @param enable true to enable, false to disable
   * @return this builder instance
   */
  public VMConfiguration kvm(final boolean enable) {
    if (enable) {
      return this.flag("enable-kvm");
    }
    // Remove the flag if it exists
    this.arguments.removeIf(arg -> arg.equals("-enable-kvm"));
    return this;
  }

  /**
   * Sets display type.
   *
   * @param displayType the display type (e.g., "sdl", "gtk", "none")
   * @return this builder instance
   */
  public VMConfiguration display(final String displayType) {
    return this.option("display", displayType);
  }

  /**
   * Configures graphics settings.
   *
   * @param enable true to enable graphics, false for headless mode
   * @return this builder instance
   */
  public VMConfiguration graphics(final boolean enable) {
    if (!enable) {
      return this.flag("nographic");
    }
    // Remove the flag if it exists
    this.arguments.removeIf(arg -> arg.equals("-nographic"));
    return this;
  }

  /**
   * Configures audio driver.
   *
   * @param driver the audio driver to use
   * @return this builder instance
   */
  public VMConfiguration audio(final String driver) {
    return this.option("audio", driver);
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
    return this.option("drive", "file=fat:rw:" + size);
  }

  /**
   * Adds a custom QEMU option.
   *
   * @param key   the option key
   * @param value the option value
   * @return this builder instance
   */
  public VMConfiguration option(final String key, final String value) {
    this.arguments.removeIf(arg -> arg.equals("-" + key));
    final int valueIndex = this.arguments.indexOf("-" + key) + 1;
    if (valueIndex > 0 && valueIndex < this.arguments.size()) {
      this.arguments.remove(valueIndex);
    }
    this.arguments.add("-" + key);
    this.arguments.add(value);
    return this;
  }

  /**
   * Adds a flag option (option without value).
   *
   * @param flag the flag name
   * @return this builder instance
   */
  public VMConfiguration flag(final String flag) {
    if (!this.arguments.contains("-" + flag)) {
      this.arguments.add("-" + flag);
    }
    return this;
  }

  /**
   * Returns an unmodifiable view of the configuration arguments.
   *
   * @return the configured arguments list
   */
  public List<String> getArguments() {
    return Collections.unmodifiableList(this.arguments);
  }

  /**
   * Builds the argument array for QEMU.
   *
   * @return a string array of QEMU arguments
   */
  public String[] buildArgs() {
    return this.arguments.toArray(new String[0]);
  }
}
