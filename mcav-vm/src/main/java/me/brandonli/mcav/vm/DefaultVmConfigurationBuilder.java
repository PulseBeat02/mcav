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

final class DefaultVmConfigurationBuilder implements VmConfigurationBuilder {

  private static final String OPTION_PREFIX = "-";

  private static final String MEMORY_OPTION = "m";
  private static final String MEGABYTES_SUFFIX = "M";

  private static final int REPEATABLE_ENTRY_SIZE = 2;

  private static final String MEMORY_NOT_POSITIVE_MESSAGE = "Memory %s must be positive!";

  private static final String DISK_PATH_NULL_MESSAGE = "Disk path must not be null!";

  private static final String KEY_NULL_MESSAGE = "Key must not be null!";
  private static final String KEY_BLANK_MESSAGE = "Key must not be blank!";
  private static final String VALUE_NULL_MESSAGE = "Value must not be null!";

  private final Map<String, String> options;
  private final List<String> repeatable;
  private final Set<String> flags;

  DefaultVmConfigurationBuilder() {
    this.options = new LinkedHashMap<>();
    this.repeatable = new ArrayList<>();
    this.flags = new LinkedHashSet<>();
  }

  @Override
  public VmConfigurationBuilder memory(final int memoryMegabytes) {
    Preconditions.checkArgument(memoryMegabytes > 0, MEMORY_NOT_POSITIVE_MESSAGE, memoryMegabytes);

    return this.option(MEMORY_OPTION, memoryMegabytes + MEGABYTES_SUFFIX);
  }

  @Override
  public VmConfigurationBuilder memory(final int amount, final String unit) {
    Preconditions.checkArgument(amount > 0, MEMORY_NOT_POSITIVE_MESSAGE, amount);
    Preconditions.checkNotNull(unit, "Unit must not be null!");

    return this.option(MEMORY_OPTION, amount + unit);
  }

  @Override
  public VmConfigurationBuilder cores(final int cores) {
    Preconditions.checkArgument(cores > 0, "Cores %s must be positive!", cores);

    final String value = String.valueOf(cores);
    return this.option("smp", value);
  }

  @Override
  public VmConfigurationBuilder cdrom(final String isoPath) {
    Preconditions.checkNotNull(isoPath, "ISO path must not be null!");

    return this.option("cdrom", isoPath);
  }

  @Override
  public VmConfigurationBuilder firstHardDisk(final String imagePath) {
    Preconditions.checkNotNull(imagePath, DISK_PATH_NULL_MESSAGE);

    return this.option("hda", imagePath);
  }

  @Override
  public VmConfigurationBuilder secondHardDisk(final String imagePath) {
    Preconditions.checkNotNull(imagePath, DISK_PATH_NULL_MESSAGE);

    return this.option("hdb", imagePath);
  }

  @Override
  public VmConfigurationBuilder boot(final String bootOrder) {
    Preconditions.checkNotNull(bootOrder, "Boot order must not be null!");

    return this.option("boot", bootOrder);
  }

  @Override
  public VmConfigurationBuilder network(final String networkConfiguration) {
    Preconditions.checkNotNull(networkConfiguration, "Network configuration must not be null!");

    return this.option("nic", networkConfiguration);
  }

  @Override
  public VmConfigurationBuilder cpu(final String model) {
    Preconditions.checkNotNull(model, "CPU model must not be null!");

    return this.option("cpu", model);
  }

  @Override
  public VmConfigurationBuilder machine(final String machineType) {
    Preconditions.checkNotNull(machineType, "Machine type must not be null!");

    return this.option("machine", machineType);
  }

  @Override
  public VmConfigurationBuilder accelerator(final String accelerator) {
    Preconditions.checkNotNull(accelerator, "Accelerator must not be null!");

    return this.option("accel", accelerator);
  }

  @Override
  public VmConfigurationBuilder vga(final String adapter) {
    Preconditions.checkNotNull(adapter, "Adapter must not be null!");

    return this.option("vga", adapter);
  }

  @Override
  public VmConfigurationBuilder drive(final String drive) {
    Preconditions.checkNotNull(drive, "Drive must not be null!");

    return this.repeatable("drive", drive);
  }

  @Override
  public VmConfigurationBuilder device(final String device) {
    Preconditions.checkNotNull(device, "Device must not be null!");

    return this.repeatable("device", device);
  }

  @Override
  public VmConfigurationBuilder option(final String key, final String value) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);
    Preconditions.checkNotNull(value, VALUE_NULL_MESSAGE);
    Preconditions.checkArgument(!key.isBlank(), KEY_BLANK_MESSAGE);

    this.options.put(key, value);
    return this;
  }

  @Override
  public VmConfigurationBuilder repeatable(final String key, final String value) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);
    Preconditions.checkNotNull(value, VALUE_NULL_MESSAGE);
    Preconditions.checkArgument(!key.isBlank(), KEY_BLANK_MESSAGE);

    this.repeatable.add(OPTION_PREFIX + key);
    this.repeatable.add(value);
    return this;
  }

  @Override
  public VmConfigurationBuilder flag(final String flag) {
    Preconditions.checkNotNull(flag, "Flag must not be null!");
    Preconditions.checkArgument(!flag.isBlank(), "Flag must not be blank!");

    this.flags.add(flag);
    return this;
  }

  @Override
  public VmConfigurationBuilder remove(final String key) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);

    this.options.remove(key);
    this.flags.remove(key);

    final String argument = OPTION_PREFIX + key;
    final List<String> kept = new ArrayList<>();
    for (int index = 0; index < this.repeatable.size(); index += REPEATABLE_ENTRY_SIZE) {
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

  @Override
  public VmConfiguration build() {
    return VmConfiguration.of(this.options, this.repeatable, this.flags);
  }
}
