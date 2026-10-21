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
import org.checkerframework.checker.nullness.qual.Nullable;

// Seatbelt, the sandbox of macOS that Chromium itself uses: a process that puts itself in it with a profile reaches
// only what the profile allows, and so does every process it starts afterwards. Unlike Landlock it holds for the whole
// process at once, and it cannot be lifted. It is reached through the functions of a library, the C library of the
// process on macOS, and runs in the helper process, which has only the browser module and JCEF on its class path.
final class Seatbelt {

  static final String MISSING = "this system has no Seatbelt (sandbox_init)";

  private static final String SANDBOX_INIT = "sandbox_init";
  private static final String FREE_ERROR = "sandbox_free_error";
  private static final String UNCALLABLE = "The C library could not be called!";
  private static final Integer SUCCESS = 0;
  private static final long PROFILE_TEXT = 0L;

  private static final String PROFILE_START = "(version 1)\n(allow default)\n";
  private static final String DENY_READING = "deny file-read-data";
  private static final String ALLOW_READING = "allow file-read-data";
  private static final String DENY_WRITING = "(deny file-write*)\n";
  private static final String ALLOW_WRITING = "allow file-write*";

  private final SymbolLookup library;

  Seatbelt(final SymbolLookup library) {
    this.library = library;
  }

  static Seatbelt ofSystem() {
    return new Seatbelt(Linker.nativeLinker().defaultLookup());
  }

  // Puts the calling process, and every process it starts afterwards, in the sandbox of a profile.
  // sandbox_init is called through the restricted native linker; the helper starts with native access enabled
  @SuppressWarnings("restricted")
  void restrictProcess(final String profile) throws IOException {
    final @Nullable MemorySegment init = this.library.find(SANDBOX_INIT).orElse(null);
    final @Nullable MemorySegment freeError = this.library.find(FREE_ERROR).orElse(null);
    if (init == null || freeError == null) {
      final String message = "The process cannot be restricted, because %s!".formatted(MISSING);
      throw new IOException(message);
    }

    final Linker linker = Linker.nativeLinker();
    final MethodHandle sandboxInitCall = linker.downcallHandle(
      init,
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );
    final MethodHandle freeCall = linker.downcallHandle(freeError, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment error = arena.allocate(ValueLayout.ADDRESS);
      final Object result = call(sandboxInitCall, arena.allocateFrom(profile), PROFILE_TEXT, error);
      if (SUCCESS.equals(result)) {
        return;
      }
      final MemorySegment message = error.get(ValueLayout.ADDRESS, 0);
      if (message.address() == 0) {
        throw new IOException("Seatbelt refused the profile without a reason!");
      }
      // the library hands over a C string of its own, which is given back to it once read
      final String reason = message.reinterpret(Long.MAX_VALUE).getString(0);
      call(freeCall, message);
      final String refusal = "Seatbelt refused the profile (%s)!".formatted(reason);
      throw new IOException(refusal);
    }
  }

  @VisibleForTesting
  static @Nullable Object call(final MethodHandle function, final Object... arguments) {
    try {
      return function.invokeWithArguments(arguments);
    } catch (final Throwable exception) {
      if (exception instanceof final VirtualMachineError fatal) {
        throw fatal;
      }

      throw new IllegalStateException(UNCALLABLE, exception);
    }
  }

  // Writes the profile of a confinement: everything may be read and changed as before, except that nothing in the
  // hidden folders may be read, files or folder listings, apart from the readable and writable paths, and nothing may
  // be changed apart from the writable paths. The names and attributes of files stay visible, as with Landlock.
  // Seatbelt matches the real paths of files, so the paths are given as they resolve; a later rule of a profile
  // overrides an earlier one.
  static String profile(final List<Path> hidden, final List<Path> readable, final List<Path> writable) {
    final StringBuilder profile = new StringBuilder(PROFILE_START);
    rule(profile, DENY_READING, hidden);
    rule(profile, ALLOW_READING, readable);
    rule(profile, ALLOW_READING, writable);
    profile.append(DENY_WRITING);
    rule(profile, ALLOW_WRITING, writable);

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

  // Writes a path as a string of the profile language, whose strings escape quotes and backslashes.
  @VisibleForTesting
  static String quote(final String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  boolean isAvailable() {
    return this.library.find(SANDBOX_INIT).isPresent() && this.library.find(FREE_ERROR).isPresent();
  }
}
