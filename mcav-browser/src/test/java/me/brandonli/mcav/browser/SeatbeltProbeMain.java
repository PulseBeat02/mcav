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

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Confines its process as a helper on macOS does, for the server folder, socket and natives of its first three
 * arguments, and then reads the files of the next two, writes into the folder of the session and the file of the last
 * argument, starts {@code cat} on the first file, and asks the system whether the process is in a sandbox.
 */
final class SeatbeltProbeMain {

  private SeatbeltProbeMain() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static void main(final String[] arguments) throws Throwable {
    final Path server = Path.of(arguments[0]);
    final Path socket = Path.of(arguments[1]);
    final HelperConfiguration configuration = new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      socket,
      Path.of(arguments[2]),
      socket.resolveSibling("profile"),
      URI.create("https://example.com/"),
      640,
      480,
      1,
      30,
      false,
      false,
      false,
      true,
      server,
      List.of()
    );
    System.out.println("said " + ChromiumConfinement.confine(configuration, false, true));
    System.out.println("server folder: " + read(Path.of(arguments[3])));
    System.out.println("other session: " + read(Path.of(arguments[4])));
    Files.writeString(socket.resolveSibling("profile.txt"), "profile", StandardCharsets.UTF_8);
    System.out.println("session: written");
    System.out.println("outside: " + write(Path.of(arguments[5])));
    final Process cat = new ProcessBuilder("/bin/cat", arguments[3]).redirectErrorStream(true).start();
    cat.getInputStream().readAllBytes();
    System.out.println("started cat: " + (cat.waitFor() == 0 ? "read" : "refused"));
    System.out.println("sandboxed: " + (sandboxCheck() != 0));
  }

  private static String read(final Path file) {
    try {
      Files.readString(file, StandardCharsets.UTF_8);
      return "read";
    } catch (final IOException failure) {
      return describe(failure);
    }
  }

  private static String write(final Path file) {
    try {
      Files.writeString(file, "outside", StandardCharsets.UTF_8);
      return "written";
    } catch (final IOException failure) {
      return describe(failure);
    }
  }

  // Seatbelt refuses with EPERM, which Java reports as a plain file system exception, Landlock with EACCES
  private static String describe(final IOException failure) {
    final boolean refused =
      failure instanceof AccessDeniedException ||
      (failure instanceof final FileSystemException system && "Operation not permitted".equals(system.getReason()));
    return refused ? "denied" : "failed: " + failure;
  }

  @SuppressWarnings("restricted")
  private static int sandboxCheck() throws Throwable {
    final Linker linker = Linker.nativeLinker();
    final MemorySegment function = linker.defaultLookup().findOrThrow("sandbox_check");
    final MethodHandle check = linker.downcallHandle(
      function,
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    return (int) check.invokeExact((int) ProcessHandle.current().pid(), MemorySegment.NULL, 0);
  }
}
