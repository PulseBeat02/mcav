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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Seatbelt, the sandbox of macOS that Chromium itself uses: a process that puts itself in it with a profile reaches only
 * what the profile allows, and so does every process it starts afterwards. Unlike Landlock it holds for the whole
 * process at once, and it cannot be lifted. It is reached through the functions of a library, the C library of the
 * process on macOS, and runs in the helper process, which has only the browser module and JCEF on its class path.
 */
final class Seatbelt {

  /** Why a system has no Seatbelt. */
  static final String MISSING = "this system has no Seatbelt (sandbox_init)";

  private static final String INIT = "sandbox_init";
  private static final String FREE_ERROR = "sandbox_free_error";
  private static final String REFUSED = "Seatbelt refused the profile: ";
  private static final String NO_REASON = "no reason given";
  private static final String UNCALLABLE = "The C library could not be called";
  private static final Integer SUCCESS = 0;
  // the profile is compiled from its text, as sandbox-exec -p does
  private static final long PROFILE_TEXT = 0L;

  private final SymbolLookup library;

  /**
   * Creates the sandbox of a library.
   *
   * @param library the library that has {@code sandbox_init} and {@code sandbox_free_error}, the C library on macOS
   */
  Seatbelt(final SymbolLookup library) {
    this.library = library;
  }

  /**
   * Gets the sandbox of this process's C library.
   *
   * @return the sandbox, which only macOS has
   */
  static Seatbelt ofSystem() {
    return new Seatbelt(Linker.nativeLinker().defaultLookup());
  }

  /**
   * Checks whether the library has the functions of the sandbox.
   *
   * @return true on macOS
   */
  boolean isAvailable() {
    return this.library.find(INIT).isPresent() && this.library.find(FREE_ERROR).isPresent();
  }

  /**
   * Puts the calling process, and every process it starts afterwards, in the sandbox of a profile.
   *
   * @param profile the profile
   * @throws IOException if the library has no Seatbelt or refuses the profile
   */
  @SuppressWarnings("restricted")
  void restrictProcess(final String profile) throws IOException {
    final Optional<MemorySegment> init = this.library.find(INIT);
    final Optional<MemorySegment> freeError = this.library.find(FREE_ERROR);
    if (init.isEmpty() || freeError.isEmpty()) {
      throw new IOException(MISSING);
    }
    final Linker linker = Linker.nativeLinker();
    final MethodHandle initCall = linker.downcallHandle(
      init.get(),
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    final MethodHandle freeCall = linker.downcallHandle(freeError.get(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment error = arena.allocate(ValueLayout.ADDRESS);
      final Object result = call(initCall, arena.allocateFrom(profile), PROFILE_TEXT, error);
      if (SUCCESS.equals(result)) {
        return;
      }
      final MemorySegment message = error.get(ValueLayout.ADDRESS, 0);
      if (message.address() == 0) {
        throw new IOException(REFUSED + NO_REASON);
      }
      // the library hands over a C string of its own, which is given back to it once read
      final String reason = message.reinterpret(Long.MAX_VALUE).getString(0);
      call(freeCall, message);
      throw new IOException(REFUSED + reason);
    }
  }

  /**
   * Calls a function of the C library.
   *
   * @param function  the function
   * @param arguments its arguments
   * @return its result, null for a function that returns nothing
   * @throws IllegalStateException if the JVM could not make the call
   */
  @VisibleForTesting
  static @Nullable Object call(final MethodHandle function, final Object... arguments) {
    try {
      return function.invokeWithArguments(arguments);
    } catch (final Throwable failure) {
      // a function of the C library throws nothing itself: this is the JVM, such as a refused native access; a virtual
      // machine error, such as running out of memory, is no answer of the call and goes on as it is
      if (failure instanceof final VirtualMachineError fatal) {
        throw fatal;
      }
      throw new IllegalStateException(UNCALLABLE, failure);
    }
  }

  /**
   * Writes the profile of a confinement: everything may be read and changed as before, except that nothing in the hidden
   * folders may be read, files or folder listings, apart from the readable and writable paths, and nothing may be
   * changed apart from the writable paths. The names and attributes of files stay visible, as with Landlock. Seatbelt
   * matches the real paths of files, so the paths are given as they resolve; a later rule of a profile overrides an
   * earlier one.
   *
   * @param hidden   the folders that may not be read
   * @param readable the files and folders that may be read even in a hidden folder
   * @param writable the files and folders that may be changed
   * @return the profile
   */
  static String profile(final List<Path> hidden, final List<Path> readable, final List<Path> writable) {
    final StringBuilder profile = new StringBuilder("(version 1)\n(allow default)\n");
    rule(profile, "deny file-read-data", hidden);
    rule(profile, "allow file-read-data", readable);
    rule(profile, "allow file-read-data", writable);
    profile.append("(deny file-write*)\n");
    rule(profile, "allow file-write*", writable);
    return profile.toString();
  }

  private static void rule(final StringBuilder profile, final String action, final List<Path> paths) {
    if (paths.isEmpty()) {
      return;
    }
    profile.append('(').append(action);
    for (final Path path : paths) {
      profile.append(" (subpath ").append(quote(path.toString())).append(')');
    }
    profile.append(")\n");
  }

  /**
   * Writes a path as a string of the profile language, whose strings escape quotes and backslashes.
   *
   * @param text the path
   * @return the string, in quotes
   */
  @VisibleForTesting
  static String quote(final String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
