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

/**
 * Landlock, the sandbox of the Linux kernel (5.13 and later) that a process can put itself in without privileges: a
 * thread that restricts itself, and every thread and process it starts afterwards, reaches only the files and folders
 * its rules name, in the ways they allow. The restriction cannot be lifted, and the other threads of the process stay
 * free. It runs in the helper process, which has only the browser module and JCEF on its class path.
 */
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
  // the rights of the first version are its 13 lowest bits; making a device file is among them, and never granted
  private static final long FIRST_VERSION_RIGHTS = (1L << 13) - 1;
  private static final long REFER = 1L << 13;
  private static final long TRUNCATE = 1L << 14;
  private static final long DEVICE_CONTROL = 1L << 15;
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

  private Landlock() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Asks the kernel which version of Landlock it has. Linux only.
   *
   * @return the version, 1 or more, or the negated error number of the kernel when it has none
   */
  static int version() {
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment state = arena.allocate(Calls.STATE);
      final long result = invoke(Calls.SYSCALL, state, CREATE_RULESET, 0L, 0L, CREATE_RULESET_VERSION, 0L);
      return versionOf(result, Calls.errorOf(state));
    }
  }

  /**
   * Reads the answer of the kernel to the question of {@link #version()}.
   *
   * @param result the result of the system call
   * @param error  the error number it left
   * @return the version, or the negated error number
   */
  @VisibleForTesting
  static int versionOf(final long result, final int error) {
    return result < 0 ? -error : (int) result;
  }

  /**
   * Says why a kernel has no Landlock.
   *
   * @param version what {@link #version()} gave, less than 1
   * @return the reason
   */
  static String describeMissing(final int version) {
    final int error = -version;
    return switch (error) {
      case NO_SUCH_SYSTEM_CALL -> "this kernel has no Landlock, which Linux has since 5.13";
      case NOT_SUPPORTED -> "Landlock is turned off in this kernel (its lsm= boot option)";
      default -> "Landlock is not available here (error " + error + ")";
    };
  }

  /**
   * Gets the rights a ruleset of a version of Landlock handles: those it denies unless a rule grants them.
   *
   * @param version the version of the kernel
   * @return the rights
   */
  @VisibleForTesting
  static long handledRights(final int version) {
    long rights = FIRST_VERSION_RIGHTS;
    if (version >= 2) {
      rights |= REFER;
    }
    if (version >= 3) {
      rights |= TRUNCATE;
    }
    if (version >= 5) {
      rights |= DEVICE_CONTROL;
    }
    return rights;
  }

  /**
   * Gets the rights of a rule: to read and run, and to change as well if it is writable; a file has no rights of a
   * folder.
   *
   * @param rule        the rule
   * @param isDirectory whether its path is a folder
   * @param handled     the rights of the ruleset
   * @return the rights
   */
  @VisibleForTesting
  static long rightsOf(final Rule rule, final boolean isDirectory, final long handled) {
    long rights = rule.writable() ? READ_RIGHTS | WRITE_RIGHTS : READ_RIGHTS;
    if (!isDirectory) {
      rights &= FILE_RIGHTS;
    }
    return rights & handled;
  }

  /**
   * Restricts the calling thread, and every thread and process it starts afterwards, to the rules. A rule whose path
   * cannot be opened, such as one deleted meanwhile, is left out.
   *
   * @param version the version of Landlock the kernel has, 1 or more
   * @param rules   what the thread may reach
   * @throws IOException if the kernel refuses the rules or the restriction
   */
  static void restrictThread(final int version, final List<Rule> rules) throws IOException {
    final long handled = handledRights(version);
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment state = arena.allocate(Calls.STATE);
      final MemorySegment attributes = arena.allocate(ValueLayout.JAVA_LONG);
      attributes.set(ValueLayout.JAVA_LONG, 0, handled);
      final long created = invoke(Calls.SYSCALL, state, CREATE_RULESET, attributes.address(), (long) Long.BYTES, 0L, 0L);
      final long ruleset = check(created, Calls.errorOf(state), "create a ruleset");
      try {
        for (final Rule rule : rules) {
          addRule(arena, state, ruleset, rule, handled);
        }
        final long unprivileged = invoke(Calls.PRCTL, state, SET_NO_NEW_PRIVILEGES, 1L, 0L, 0L, 0L);
        check(unprivileged, Calls.errorOf(state), "keep the thread from gaining privileges");
        final long restricted = invoke(Calls.SYSCALL, state, RESTRICT_SELF, ruleset, 0L, 0L, 0L);
        check(restricted, Calls.errorOf(state), "restrict the thread");
      } finally {
        invoke(Calls.CLOSE, state, (int) ruleset);
      }
    }
  }

  private static void addRule(final Arena arena, final MemorySegment state, final long ruleset, final Rule rule, final long handled)
    throws IOException {
    final Path path = rule.path();
    final boolean isDirectory = Files.isDirectory(path);
    final MemorySegment name = arena.allocateFrom(path.toString());
    final long descriptor = invoke(Calls.OPEN, state, name, OPEN_PATH | OPEN_CLOSE_ON_EXEC);
    if (descriptor < 0) {
      return;
    }
    try {
      // struct landlock_path_beneath_attr, packed: the rights, then the descriptor
      final MemorySegment beneath = arena.allocate(Long.BYTES + Integer.BYTES);
      beneath.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, rightsOf(rule, isDirectory, handled));
      beneath.set(ValueLayout.JAVA_INT_UNALIGNED, Long.BYTES, (int) descriptor);
      final long added = invoke(Calls.SYSCALL, state, ADD_RULE, ruleset, RULE_PATH_BENEATH, beneath.address(), 0L);
      check(added, Calls.errorOf(state), "add the rule of " + path);
    } finally {
      invoke(Calls.CLOSE, state, (int) descriptor);
    }
  }

  /**
   * Checks the result of a call of the kernel.
   *
   * @param result the result
   * @param error  the error number the call left
   * @param action what the call did, for the message
   * @return the result
   * @throws IOException if the result says the call failed
   */
  @VisibleForTesting
  static long check(final long result, final int error, final String action) throws IOException {
    if (result < 0) {
      throw new IOException("Landlock could not " + action + " (error " + error + ")");
    }
    return result;
  }

  /**
   * Calls a function of the C library.
   *
   * @param function  the function
   * @param arguments its arguments, after the segment its error number is captured in
   * @return its result
   * @throws IllegalStateException if the JVM could not make the call
   */
  @VisibleForTesting
  static long invoke(final MethodHandle function, final Object... arguments) {
    try {
      final Object result = function.invokeWithArguments(arguments);
      return ((Number) result).longValue();
    } catch (final Throwable failure) {
      // a function of the C library throws nothing itself: this is the JVM, such as a refused native access
      throw new IllegalStateException("The C library could not be called", failure);
    }
  }

  /**
   * A file, or a folder and everything beneath it, that a restricted thread may read and run, and change as well if
   * the rule is writable.
   *
   * @param path     the file or folder
   * @param writable whether files may be written, created, renamed and deleted there
   */
  record Rule(Path path, boolean writable) {}

  /**
   * The functions of the C library, bound when Landlock is first used, which happens on Linux only.
   */
  private static final class Calls {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERROR = STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    // the number of the call and four arguments, as many as Landlock's calls take
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

    private Calls() {
      throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // the C library is part of every process on Linux; prctl and syscall are variadic, open has an optional mode
    @SuppressWarnings("restricted")
    private static MethodHandle bind(final String name, final FunctionDescriptor descriptor, final int firstVariadic) {
      final SymbolLookup library = LINKER.defaultLookup();
      final MemorySegment function = library.findOrThrow(name);
      final Linker.Option errorNumber = Linker.Option.captureCallState("errno");
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
