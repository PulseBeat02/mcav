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
package me.brandonli.mcav.capability.installer.vlc.discovery;

import com.google.common.base.Suppliers;
import java.util.Optional;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.binding.lib.LibC;

// Sets process environment variables through the C library, so native code such as libvlc sees them; System.getenv(String) does not.
final class EnvironmentVariables {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentVariables.class);

  private static final String ASSIGNMENT_SEPARATOR = "=";

  private static final int OVERWRITE = 1;

  private final Supplier<LibC> libc;

  EnvironmentVariables(final LibC libc) {
    this(() -> libc);
  }

  // The C library loads on first use, so where JNA fails only the plugin path is lost; a failed load is retried on the next variable.
  EnvironmentVariables(final Supplier<LibC> libc) {
    this.libc = Suppliers.memoize(libc::get);
  }

  // Creates a setter for the C library of this process: msvcrt on Windows, libc everywhere else.
  static EnvironmentVariables create() {
    return new EnvironmentVariables(EnvironmentVariables::loadNativeLibrary);
  }

  private static LibC loadNativeLibrary() {
    return LibC.INSTANCE;
  }

  boolean setPosixVariable(final String name, final String value) {
    final Optional<LibC> library = this.loadLibrary();
    final @Nullable LibC loaded = library.orElse(null);
    if (loaded == null) {
      return false;
    }

    final int result = loaded.setenv(name, value, OVERWRITE);
    return result == 0;
  }

  boolean setWindowsVariable(final String name, final String value) {
    final Optional<LibC> library = this.loadLibrary();
    final @Nullable LibC loaded = library.orElse(null);
    if (loaded == null) {
      return false;
    }

    final String assignment = name + ASSIGNMENT_SEPARATOR + value;
    final int result = loaded._putenv(assignment);
    return result == 0;
  }

  private Optional<LibC> loadLibrary() {
    try {
      final LibC loaded = this.libc.get();
      return Optional.of(loaded);
    } catch (final LinkageError exception) {
      final String reason = exception.getMessage();
      LOGGER.warn("The C library of this process failed with \"{}\" while loading, so the VLC plugin path cannot be set.", reason);
      return Optional.empty();
    }
  }
}
