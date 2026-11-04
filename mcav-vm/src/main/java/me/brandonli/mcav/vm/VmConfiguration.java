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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * QEMU command-line settings for a {@link VmPlayer}, built with {@link #builder()}.
 *
 * <p>This class is immutable and thread-safe. Values are passed as individual process arguments, without a shell;
 * paths need no shell quoting. Option values are otherwise left to QEMU to validate.
 *
 * <p>Options keep the order they were first set in, and setting an option again replaces its value. Options
 * QEMU accepts more than once, such as {@code -drive} or {@code -device}, are added with
 * {@link VmConfigurationBuilder#repeatable(String, String)}.
 *
 * <p>The player supplies {@code -vnc} from {@link VmSettings}, reserves {@code -audio}, {@code -audiodev} and
 * {@code pcspk-audiodev} routing, and rejects configurations that set those options. It also supplies defaults
 * for {@code -vga std}, {@code -display none} and a USB tablet ({@code -usb -device usb-tablet}) unless overridden,
 * which reports absolute pointer positions so clicks land where they are sent. The tablet is left out when the
 * configuration adds its own with {@code device("usb-tablet")} or uses the older {@code -usbdevice} option. To choose
 * the USB controller yourself, set the {@code usb} flag or {@code usb=on} in the machine type, and to run without USB
 * at all, which also leaves out the tablet, set {@code usb=off}, as in {@code machine("q35,usb=off")}.
 *
 * {@snippet :
 * final String isoPath = "alpine.iso";
 * final int memoryMegabytes = 2048;
 * final int cores = 2;
 * final VmConfigurationBuilder builder = VmConfiguration.builder();
 * final VmConfiguration configuration = builder.cdrom(isoPath).memory(memoryMegabytes).cores(cores).build();
 * }
 *
 * @since 1.0.0
 */
public final class VmConfiguration {

  private static final String OPTION_PREFIX = "-";

  private static final int REPEATABLE_ENTRY_SIZE = 2;

  private static final String KEY_NULL_MESSAGE = "Key must not be null!";

  private final Map<String, String> options;
  private final List<String> repeatable;
  private final Set<String> flags;

  private VmConfiguration(final Map<String, String> options, final List<String> repeatable, final Set<String> flags) {
    this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    this.repeatable = List.copyOf(repeatable);
    this.flags = Collections.unmodifiableSet(new LinkedHashSet<>(flags));
  }

  static VmConfiguration of(final Map<String, String> options, final List<String> repeatable, final Set<String> flags) {
    return new VmConfiguration(options, repeatable, flags);
  }

  /**
   * Returns a builder for a configuration that starts empty.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static VmConfigurationBuilder builder() {
    return new DefaultVmConfigurationBuilder();
  }

  /**
   * Returns whether an option, a repeatable option, or a flag is set.
   *
   * @param key the option name without the leading dash
   * @return true if set
   * @throws NullPointerException if {@code key} is null
   * @since 1.0.0
   */
  public boolean has(final String key) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);

    final List<String> values = this.getAll(key);
    return this.options.containsKey(key) || this.flags.contains(key) || !values.isEmpty();
  }

  List<String> valuesOf(final Set<String> keys) {
    final List<String> values = new ArrayList<>();
    for (final Map.Entry<String, String> entry : this.options.entrySet()) {
      final String key = entry.getKey();
      if (keys.contains(key)) {
        final String value = entry.getValue();
        values.add(value);
      }
    }

    for (int index = 0; index < this.repeatable.size(); index += REPEATABLE_ENTRY_SIZE) {
      final String name = this.repeatable.get(index);
      final String key = name.substring(OPTION_PREFIX.length());
      if (keys.contains(key)) {
        final String value = this.repeatable.get(index + 1);
        values.add(value);
      }
    }

    return values;
  }

  /**
   * Returns the command-line arguments as an array.
   *
   * @return a new array; changes to it do not affect this configuration
   * @since 1.0.0
   */
  public String[] buildArguments() {
    final List<String> arguments = this.getArguments();
    return arguments.toArray(new String[0]);
  }

  /**
   * Returns the values of a repeatable option, such as every {@code -device}.
   *
   * @param key the option name without the leading dash
   * @return an unmodifiable snapshot in insertion order, empty if no repeatable values exist; singleton
   *         options and flags are not included
   * @throws NullPointerException if {@code key} is null
   * @since 1.0.0
   */
  public List<String> getAll(final String key) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);

    final String argument = "%s%s".formatted(OPTION_PREFIX, key);
    final List<String> values = new ArrayList<>();
    for (int index = 0; index < this.repeatable.size(); index += REPEATABLE_ENTRY_SIZE) {
      final String name = this.repeatable.get(index);
      if (name.equals(argument)) {
        final String value = this.repeatable.get(index + 1);
        values.add(value);
      }
    }

    return List.copyOf(values);
  }

  /**
   * Returns the value of an option. Repeatable options are read with {@link #getAll(String)}.
   *
   * @param key the option name without the leading dash
   * @return the value, or empty if the option is not set
   * @throws NullPointerException if {@code key} is null
   * @since 1.0.0
   */
  public Optional<String> find(final String key) {
    Preconditions.checkNotNull(key, KEY_NULL_MESSAGE);

    final @Nullable String value = this.options.get(key);
    return Optional.ofNullable(value);
  }

  /**
   * Returns the command-line arguments in order: options, repeatable options, then flags.
   *
   * @return the arguments, which cannot be modified
   * @since 1.0.0
   */
  public List<String> getArguments() {
    final List<String> arguments = new ArrayList<>();
    for (final Map.Entry<String, String> entry : this.options.entrySet()) {
      final String key = entry.getKey();
      final String value = entry.getValue();
      final String argument = "%s%s".formatted(OPTION_PREFIX, key);
      arguments.add(argument);
      arguments.add(value);
    }

    arguments.addAll(this.repeatable);

    for (final String flag : this.flags) {
      final String argument = "%s%s".formatted(OPTION_PREFIX, flag);
      arguments.add(argument);
    }

    return List.copyOf(arguments);
  }

  /**
   * Returns whether {@code other} is a configuration with the same options, repeatable options and flags. Repeatable
   * options must also be in the same order; the order of the other options and of the flags does not matter.
   *
   * @param other the object to compare with
   * @return whether both configurations match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final VmConfiguration configuration)) {
      return false;
    }

    return (
      this.options.equals(configuration.options) &&
      this.repeatable.equals(configuration.repeatable) &&
      this.flags.equals(configuration.flags)
    );
  }

  /**
   * Returns a hash code built from the options, the repeatable options and the flags.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.options, this.repeatable, this.flags);
  }

  /**
   * Returns a debugging representation with the options, the repeatable options and the flags.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("options", this.options)
      .add("repeatable", this.repeatable)
      .add("flags", this.flags)
      .toString();
  }
}
