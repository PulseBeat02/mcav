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

// macOS's sandbox, which Chromium itself uses: unlike Landlock it holds for the whole process at once, for good.
final class Seatbelt {

  static final String MISSING = "this system has no Seatbelt (sandbox_init)";

  private static final String SANDBOX_INITIALIZE = "sandbox_init";
  private static final String FREE_ERROR = "sandbox_free_error";

  private static final Integer SUCCESS = 0;
  private static final long PROFILE_TEXT = 0L;

  private static final char RULE_START = '(';
  private static final String SUBPATH_START = " (subpath ";
  private static final char SUBPATH_END = ')';
  private static final String RULE_END = ")\n";
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
    final Linker linker = Linker.nativeLinker();
    final SymbolLookup lookup = linker.defaultLookup();
    return new Seatbelt(lookup);
  }

  // sandbox_init is a restricted native call; the helper starts with native access enabled
  @SuppressWarnings("restricted")
  void restrictProcess(final String profile) throws IOException {
    final @Nullable MemorySegment initialize = this.library.find(SANDBOX_INITIALIZE).orElse(null);
    final @Nullable MemorySegment freeError = this.library.find(FREE_ERROR).orElse(null);
    if (initialize == null || freeError == null) {
      final String message = "Because %s, the process cannot be restricted!".formatted(MISSING);
      throw new IOException(message);
    }

    final Linker linker = Linker.nativeLinker();
    final FunctionDescriptor initializeDescriptor = FunctionDescriptor.of(
      ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS,
      ValueLayout.JAVA_LONG,
      ValueLayout.ADDRESS
    );
    final MethodHandle initializeCall = linker.downcallHandle(initialize, initializeDescriptor);
    final FunctionDescriptor freeErrorDescriptor = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    final MethodHandle freeCall = linker.downcallHandle(freeError, freeErrorDescriptor);
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment error = arena.allocate(ValueLayout.ADDRESS);
      final MemorySegment profileText = arena.allocateFrom(profile);
      final Object result = call(initializeCall, profileText, PROFILE_TEXT, error);
      if (SUCCESS.equals(result)) {
        return;
      }

      final MemorySegment nativeReason = error.get(ValueLayout.ADDRESS, 0);
      if (nativeReason.address() == 0) {
        final String message = "Seatbelt refused the profile without a reason!";
        throw new IOException(message);
      }

      // the library hands over a C string of its own, which is given back to it once read
      final MemorySegment reasonText = nativeReason.reinterpret(Long.MAX_VALUE);
      final String reason = reasonText.getString(0);
      call(freeCall, nativeReason);

      final String message = "Seatbelt refused the profile, reporting %s as the reason!".formatted(reason);
      throw new IOException(message);
    }
  }

  @VisibleForTesting
  static @Nullable Object call(final MethodHandle function, final Object... arguments) {
    try {
      return function.invokeWithArguments(arguments);
    } catch (final Throwable exception) {
      if (exception instanceof final VirtualMachineError virtualMachineError) {
        throw virtualMachineError;
      }

      final String message = "The C library could not be called!";
      throw new IllegalStateException(message, exception);
    }
  }

  // names and attributes of files stay visible, as with Landlock; Seatbelt matches real paths, and a later rule of a
  // profile overrides an earlier one
  static String profile(final List<Path> hidden, final List<Path> readable, final List<Path> writable) {
    final StringBuilder profile = new StringBuilder(PROFILE_START);
    appendRule(profile, DENY_READING, hidden);
    appendRule(profile, ALLOW_READING, readable);
    appendRule(profile, ALLOW_READING, writable);
    profile.append(DENY_WRITING);
    appendRule(profile, ALLOW_WRITING, writable);

    return profile.toString();
  }

  private static void appendRule(final StringBuilder profile, final String action, final List<Path> paths) {
    if (paths.isEmpty()) {
      return;
    }

    profile.append(RULE_START).append(action);
    for (final Path path : paths) {
      profile.append(SUBPATH_START).append(quote(path.toString())).append(SUBPATH_END);
    }

    profile.append(RULE_END);
  }

  @VisibleForTesting
  static String quote(final String text) {
    final String backslashesEscaped = text.replace("\\", "\\\\");
    final String escaped = backslashesEscaped.replace("\"", "\\\"");
    return "\"%s\"".formatted(escaped);
  }

  boolean isAvailable() {
    return this.library.find(SANDBOX_INITIALIZE).isPresent() && this.library.find(FREE_ERROR).isPresent();
  }
}
