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

// Confines its process as a macOS helper does, tries reads, writes and a child process, and asks whether it is in a
// sandbox.
final class SeatbeltProbeMain {

  private static final int NATIVES_ARGUMENT = 2;
  private static final int SERVER_FOLDER_ARGUMENT = 3;
  private static final int OTHER_SESSION_ARGUMENT = 4;
  private static final int OUTSIDE_FILE_ARGUMENT = 5;

  private static final String PROFILE_DIRECTORY = "profile";
  private static final String PROFILE_FILE = "profile.txt";
  private static final String CAT_PROGRAM = "/bin/cat";

  private static final String PAGE_URL = "https://example.com/";

  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

  private static final int FRAME_INTERVAL = 1;
  private static final int FRAME_RATE = 30;

  private static final String NOT_PERMITTED_REASON = "Operation not permitted";

  private static final String SANDBOX_CHECK = "sandbox_check";
  private static final int NO_FILTER = 0;

  private static final String READ = "read";

  private SeatbeltProbeMain() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] arguments) throws Throwable {
    final Path server = Path.of(arguments[0]);
    final Path socket = Path.of(arguments[1]);
    final boolean allowsJavaScriptJit = false;
    final boolean allowsPrivateNetworks = false;
    final boolean allowsAutoplay = false;
    final boolean confined = true;
    final HelperConfiguration configuration = new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      socket,
      Path.of(arguments[NATIVES_ARGUMENT]),
      socket.resolveSibling(PROFILE_DIRECTORY),
      URI.create(PAGE_URL),
      WIDTH,
      HEIGHT,
      FRAME_INTERVAL,
      FRAME_RATE,
      allowsJavaScriptJit,
      allowsPrivateNetworks,
      allowsAutoplay,
      confined,
      server,
      List.of()
    );
    final String confinement = ChromiumConfinement.confine(configuration, OperatingSystem.MAC);
    System.out.println("said %s".formatted(confinement));
    System.out.println("server folder: %s".formatted(read(Path.of(arguments[SERVER_FOLDER_ARGUMENT]))));
    System.out.println("other session: %s".formatted(read(Path.of(arguments[OTHER_SESSION_ARGUMENT]))));
    Files.writeString(socket.resolveSibling(PROFILE_FILE), "profile", StandardCharsets.UTF_8);
    System.out.println("session: written");
    System.out.println("outside: %s".formatted(write(Path.of(arguments[OUTSIDE_FILE_ARGUMENT]))));
    final Process cat = new ProcessBuilder(CAT_PROGRAM, arguments[SERVER_FOLDER_ARGUMENT]).redirectErrorStream(true).start();
    cat.getInputStream().readAllBytes();
    System.out.println("started cat: %s".formatted(cat.waitFor() == 0 ? READ : "refused"));
    System.out.println("sandboxed: %s".formatted(sandboxCheck() != 0));
  }

  private static String read(final Path file) {
    try {
      Files.readString(file, StandardCharsets.UTF_8);

      return READ;
    } catch (final IOException exception) {
      return describe(exception);
    }
  }

  private static String write(final Path file) {
    try {
      Files.writeString(file, "outside", StandardCharsets.UTF_8);

      return "written";
    } catch (final IOException exception) {
      return describe(exception);
    }
  }

  // Seatbelt refuses with EPERM, which Java reports as a plain file system exception, Landlock with EACCES
  private static String describe(final IOException failure) {
    final boolean refused =
      failure instanceof AccessDeniedException ||
      (failure instanceof final FileSystemException fileSystemException && NOT_PERMITTED_REASON.equals(fileSystemException.getReason()));
    return refused ? "denied" : "failed: %s".formatted(failure);
  }

  // sandbox_check is called through the restricted native linker; the probe runs with native access enabled
  @SuppressWarnings("restricted")
  private static int sandboxCheck() throws Throwable {
    final Linker linker = Linker.nativeLinker();
    final MemorySegment function = linker.defaultLookup().findOrThrow(SANDBOX_CHECK);
    final MethodHandle check = linker.downcallHandle(
      function,
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );
    return (int) check.invokeExact((int) ProcessHandle.current().pid(), MemorySegment.NULL, NO_FILTER);
  }
}
