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
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Linux's unprivileged sandbox (5.13 and later): a restricted thread, and what it starts afterwards, reaches only what
// its rules allow, for good, while the other threads of the process stay free.
final class Landlock {

  // the system call numbers of Landlock are the same on every architecture
  private static final long CREATE_RULESET = 444;
  private static final long ADD_RULE = 445;
  private static final long RESTRICT_SELF = 446;

  private static final long CREATE_RULESET_VERSION = 1;
  private static final long RULE_PATH_BENEATH = 1;

  private static final int SET_NO_NEW_PRIVILEGES = 38;

  private static final int OPEN_PATH = 0x20_0000;
  private static final int OPEN_CLOSE_ON_EXEC = 0x8_0000;

  private static final int NO_SUCH_SYSTEM_CALL = 38;
  private static final int NOT_SUPPORTED = 95;

  private static final long EXECUTE = 1L;
  private static final long WRITE_FILE = 1L << 1;
  private static final long READ_FILE = 1L << 2;
  private static final long READ_DIRECTORY = 1L << 3;
  private static final long REMOVE_DIRECTORY = 1L << 4;
  private static final long REMOVE_FILE = 1L << 5;
  private static final long MAKE_DIRECTORY = 1L << 7;
  private static final long MAKE_REGULAR_FILE = 1L << 8;
  private static final long MAKE_SOCKET = 1L << 9;
  private static final long MAKE_FIFO = 1L << 10;
  private static final long MAKE_SYMBOLIC_LINK = 1L << 12;
  private static final long REFER = 1L << 13;
  private static final long TRUNCATE = 1L << 14;
  private static final long DEVICE_CONTROL = 1L << 15;

  private static final long FIRST_VERSION_RIGHTS = (1L << 13) - 1;
  private static final long FILE_RIGHTS = EXECUTE | WRITE_FILE | READ_FILE | TRUNCATE | DEVICE_CONTROL;
  private static final long READ_RIGHTS = EXECUTE | READ_FILE | READ_DIRECTORY;
  private static final long WRITE_RIGHTS =
    WRITE_FILE |
    REMOVE_DIRECTORY |
    REMOVE_FILE |
    MAKE_DIRECTORY |
    MAKE_REGULAR_FILE |
    MAKE_SOCKET |
    MAKE_FIFO |
    MAKE_SYMBOLIC_LINK |
    REFER |
    TRUNCATE |
    DEVICE_CONTROL;

  private static final int REFER_VERSION = 2;
  private static final int TRUNCATE_VERSION = 3;
  private static final int DEVICE_CONTROL_VERSION = 5;

  private Landlock() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static int version() {
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment state = arena.allocate(Calls.STATE);
      final long result = invoke(Calls.SYSCALL, state, CREATE_RULESET, 0L, 0L, CREATE_RULESET_VERSION, 0L);
      return versionOf(result, Calls.errorOf(state));
    }
  }

  @VisibleForTesting
  static int versionOf(final long result, final int error) {
    return result < 0 ? -error : (int) result;
  }

  static String describeMissing(final int version) {
    final int error = -version;
    return switch (error) {
      case NO_SUCH_SYSTEM_CALL -> "this kernel has no Landlock, which Linux has since 5.13";
      case NOT_SUPPORTED -> "Landlock is turned off in this kernel (its lsm= boot option)";
      default -> "error %s from the kernel keeps Landlock off".formatted(error);
    };
  }

  // the rights a ruleset denies unless a rule grants them
  @VisibleForTesting
  static long handledRights(final int version) {
    long rights = FIRST_VERSION_RIGHTS;
    if (version >= REFER_VERSION) {
      rights |= REFER;
    }

    if (version >= TRUNCATE_VERSION) {
      rights |= TRUNCATE;
    }

    if (version >= DEVICE_CONTROL_VERSION) {
      rights |= DEVICE_CONTROL;
    }

    return rights;
  }

  // a file has no rights of a folder
  @VisibleForTesting
  static long rightsOf(final Rule rule, final PathKind kind, final long handled) {
    long rights = switch (rule.access()) {
      case READ_ONLY -> READ_RIGHTS;
      case READ_WRITE -> READ_RIGHTS | WRITE_RIGHTS;
    };
    if (kind == PathKind.FILE) {
      rights &= FILE_RIGHTS;
    }

    return rights & handled;
  }

  // a rule whose path cannot be opened, such as one deleted meanwhile, is left out
  static void restrictThread(final int version, final List<Rule> rules) throws IOException {
    final long handled = handledRights(version);
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment state = arena.allocate(Calls.STATE);
      final MemorySegment attributes = arena.allocate(ValueLayout.JAVA_LONG);
      attributes.set(ValueLayout.JAVA_LONG, 0, handled);
      final long created = invoke(Calls.SYSCALL, state, CREATE_RULESET, attributes.address(), (long) Long.BYTES, 0L, 0L);
      final long ruleset = check(created, Calls.errorOf(state), "create a ruleset");
      try {
        addRules(arena, state, ruleset, rules, handled);
        final long unprivileged = invoke(Calls.PRCTL, state, SET_NO_NEW_PRIVILEGES, 1L, 0L, 0L, 0L);
        check(unprivileged, Calls.errorOf(state), "keep the thread from gaining privileges");
        final long restricted = invoke(Calls.SYSCALL, state, RESTRICT_SELF, ruleset, 0L, 0L, 0L);
        check(restricted, Calls.errorOf(state), "restrict the thread");
      } finally {
        invoke(Calls.CLOSE, state, (int) ruleset);
      }
    }
  }

  private static void addRules(final Arena arena, final MemorySegment state, final long ruleset, final List<Rule> rules, final long handled)
    throws IOException {
    for (final Rule rule : rules) {
      addRule(arena, state, ruleset, rule, handled);
    }
  }

  private static void addRule(final Arena arena, final MemorySegment state, final long ruleset, final Rule rule, final long handled)
    throws IOException {
    final Path path = rule.path();
    final boolean isDirectory = Files.isDirectory(path);
    final PathKind kind = isDirectory ? PathKind.FOLDER : PathKind.FILE;
    final MemorySegment name = arena.allocateFrom(path.toString());
    final long descriptor = invoke(Calls.OPEN, state, name, OPEN_PATH | OPEN_CLOSE_ON_EXEC);
    if (descriptor < 0) {
      return;
    }

    try {
      // struct landlock_path_beneath_attr, packed: the rights, then the descriptor
      final MemorySegment beneath = arena.allocate(Long.BYTES + Integer.BYTES);
      beneath.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, rightsOf(rule, kind, handled));
      beneath.set(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES, (int) descriptor);
      final long added = invoke(Calls.SYSCALL, state, ADD_RULE, ruleset, RULE_PATH_BENEATH, beneath.address(), 0L);
      check(added, Calls.errorOf(state), "add the rule of %s".formatted(path));
    } finally {
      invoke(Calls.CLOSE, state, (int) descriptor);
    }
  }

  @VisibleForTesting
  static long check(final long result, final int error, final String action) throws IOException {
    if (result < 0) {
      final String message = "Landlock could not %s because of error %s from the kernel!".formatted(action, error);
      throw new IOException(message);
    }

    return result;
  }

  @VisibleForTesting
  static long invoke(final MethodHandle function, final Object... arguments) {
    try {
      final Object result = function.invokeWithArguments(arguments);
      return ((Number) result).longValue();
    } catch (final Throwable exception) {
      // ThrowableUtils is not on the class path of the helper
      if (exception instanceof final VirtualMachineError fatal) {
        throw fatal;
      }

      final String message = "The C library could not be called!";
      throw new IllegalStateException(message, exception);
    }
  }

  record Rule(Path path, Access access) {}

  enum Access {
    READ_ONLY,
    READ_WRITE,
  }

  enum PathKind {
    FOLDER,
    FILE,
  }

  // bound on first use, which happens on Linux only
  private static final class Calls {

    private static final String ERROR_NUMBER = "errno";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERROR = STATE.varHandle(MemoryLayout.PathElement.groupElement(ERROR_NUMBER));

    private static final MethodHandle SYSCALL = bind(
      "syscall",
      FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG
      ),
      1
    );
    private static final MethodHandle PRCTL = bind(
      "prctl",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG
      ),
      1
    );
    private static final MethodHandle OPEN = bind(
      "open",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
      2
    );
    private static final MethodHandle CLOSE = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), -1);

    // the C library is part of every process on Linux; prctl and syscall are variadic, open has an optional mode
    @SuppressWarnings("restricted")
    private static MethodHandle bind(final String name, final FunctionDescriptor descriptor, final int firstVariadic) {
      final SymbolLookup library = LINKER.defaultLookup();
      final MemorySegment function = library.findOrThrow(name);
      final Linker.Option errorNumber = Linker.Option.captureCallState(ERROR_NUMBER);
      if (firstVariadic < 0) {
        return LINKER.downcallHandle(function, descriptor, errorNumber);
      }

      return LINKER.downcallHandle(function, descriptor, Linker.Option.firstVariadicArg(firstVariadic), errorNumber);
    }

    private static int errorOf(final MemorySegment state) {
      return (int) ERROR.get(state, 0L);
    }
  }
}
