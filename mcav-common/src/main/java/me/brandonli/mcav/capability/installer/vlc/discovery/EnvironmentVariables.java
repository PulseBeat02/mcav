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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.binding.lib.LibC;

/**
 * Sets environment variables of the running process through the C library, so native code such as libvlc sees
 * them. {@link System#getenv(String)} keeps returning the values the JVM started with.
 *
 * <p>The C library is loaded through JNA on the first variable that is set, not when the setter is created, so a
 * system where JNA cannot load only loses the plugin path instead of every discovery strategy.
 */
final class EnvironmentVariables {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentVariables.class);

  private final Supplier<LibC> libc;

  /**
   * Constructs a new setter that calls the specified C library.
   *
   * @param libc the C library of the process
   */
  EnvironmentVariables(final LibC libc) {
    this(() -> libc);
  }

  /**
   * Constructs a new setter that loads the C library on first use. A library that loaded is remembered; a failed
   * load is tried again on the next variable.
   *
   * @param libc loads the C library of the process
   */
  EnvironmentVariables(final Supplier<LibC> libc) {
    this.libc = Suppliers.memoize(libc::get);
  }

  /**
   * Creates a setter that calls the C library of this process, {@code msvcrt} on Windows and {@code libc}
   * everywhere else. The library is only loaded when the first variable is set.
   *
   * @return the setter
   */
  static EnvironmentVariables nativeVariables() {
    return new EnvironmentVariables(EnvironmentVariables::loadNativeLibrary);
  }

  private static LibC loadNativeLibrary() {
    return LibC.INSTANCE;
  }

  /**
   * Sets a variable with {@code setenv}, which every POSIX system provides.
   *
   * @param name  the name of the variable
   * @param value the value of the variable
   * @return true if the variable was set, false if it was rejected or the C library cannot be loaded
   */
  boolean setPosixVariable(final String name, final String value) {
    final Optional<LibC> library = this.loadLibrary();
    if (library.isEmpty()) {
      return false;
    }
    final LibC loaded = library.get();
    final int result = loaded.setenv(name, value, 1);
    return result == 0;
  }

  /**
   * Sets a variable with {@code _putenv} of the Microsoft C runtime.
   *
   * @param name  the name of the variable
   * @param value the value of the variable
   * @return true if the variable was set, false if it was rejected or the C library cannot be loaded
   */
  boolean setWindowsVariable(final String name, final String value) {
    final Optional<LibC> library = this.loadLibrary();
    if (library.isEmpty()) {
      return false;
    }
    final LibC loaded = library.get();
    final String assignment = name + "=" + value;
    final int result = loaded._putenv(assignment);
    return result == 0;
  }

  private Optional<LibC> loadLibrary() {
    try {
      final LibC loaded = this.libc.get();
      return Optional.of(loaded);
    } catch (final LinkageError error) {
      final String reason = error.getMessage();
      LOGGER.warn("The C library of this process cannot be loaded, so the VLC plugin path cannot be set: {}", reason);
      return Optional.empty();
    }
  }
}
