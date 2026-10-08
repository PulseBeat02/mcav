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
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Confines Chromium on Linux and macOS. JCEF cannot run Chromium with Chromium's own sandbox (its native library always
 * turns it off), so a page that exploits a flaw of Chromium's renderer would run code as the user of the server. The
 * helper therefore restricts Chromium before starting it: Chromium's processes cannot read the server's folder, the
 * home folder of its user or the temporary folder of the server, apart from what the browser needs there (Java, CEF,
 * the libraries and class path of the helper, and the folder of the session), and they change files only in the folder
 * of the session and the devices (and on Linux the process folder). On Linux the thread that starts Chromium restricts
 * itself with Landlock, and with it every thread and process Chromium starts, while the other threads of the helper,
 * among them the network guard and the null display, stay free. On macOS Seatbelt restricts the whole helper process,
 * whose other threads read and change nothing more than Chromium does.
 */
final class ChromiumConfinement {

  static final String CONFINED =
    "Chromium runs confined: it cannot read the server's folder, the home folder or the temporary folder, and it " +
    "writes only into the folder of its session";
  static final String NOT_ASKED = "Chromium runs without confinement, as the options of the browser allow";
  static final String NOT_SUPPORTED = "Chromium runs without confinement: MCAV confines it on Linux and macOS only";
  private static final String UNAVAILABLE = "Chromium runs without confinement: ";
  private static final Path ROOT = Path.of("/");
  private static final Path DEVICES = Path.of("/dev");
  private static final Path PROCESSES = Path.of("/proc");
  private static final String AGENT_OPTION = "-javaagent:";

  private ChromiumConfinement() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Confines Chromium for the helper of a configuration, if the configuration asks for it and the system can: on Linux
   * the calling thread and everything it starts afterwards, on macOS the whole process.
   *
   * @param configuration the configuration of the helper
   * @param isLinux       whether the helper runs on Linux
   * @param isMac         whether the helper runs on macOS
   * @return what happened, for the log of the server
   * @throws IOException if the system refused the confinement
   */
  static String confine(final HelperConfiguration configuration, final boolean isLinux, final boolean isMac) throws IOException {
    return confine(configuration, isLinux, isMac, Landlock::version, Seatbelt.ofSystem());
  }

  /**
   * Confines Chromium as {@link #confine(HelperConfiguration, boolean, boolean)} does.
   *
   * @param configuration the configuration of the helper
   * @param isLinux       whether the helper runs on Linux
   * @param isMac         whether the helper runs on macOS
   * @param version       asks the kernel for its version of Landlock
   * @param seatbelt      the sandbox of macOS
   * @return what happened, for the log of the server
   * @throws IOException if the system refused the confinement
   */
  @VisibleForTesting
  static String confine(
    final HelperConfiguration configuration,
    final boolean isLinux,
    final boolean isMac,
    final IntSupplier version,
    final Seatbelt seatbelt
  ) throws IOException {
    if (!configuration.isConfined()) {
      return NOT_ASKED;
    }
    final Path session = parentOf(configuration.getSocket());
    final Path home = Path.of(System.getProperty("user.home"));
    final List<Path> hidden = List.of(configuration.getServerFolder(), home, parentOf(session));
    final List<Path> readable = new ArrayList<>();
    readable.add(Path.of(System.getProperty("java.home")));
    readable.add(configuration.getNatives());
    readable.addAll(pathsOf(System.getProperty("java.class.path")));
    readable.addAll(agentsOf(ManagementFactory.getRuntimeMXBean().getInputArguments()));
    if (isMac) {
      if (!seatbelt.isAvailable()) {
        return UNAVAILABLE + Seatbelt.MISSING;
      }
      // Seatbelt matches the paths files really have, such as /private/var for /var
      seatbelt.restrictProcess(Seatbelt.profile(realPaths(hidden), realPaths(readable), realPaths(List.of(session, DEVICES))));
      return CONFINED;
    }
    if (!isLinux) {
      return NOT_SUPPORTED;
    }
    final int available = version.getAsInt();
    if (available < 1) {
      return UNAVAILABLE + Landlock.describeMissing(available);
    }
    readable.addAll(linkedFiles(pathsOf(System.getenv("LD_LIBRARY_PATH"))));
    final List<Path> writable = List.of(session, DEVICES, PROCESSES);
    Landlock.restrictThread(available, rules(ROOT, hidden, readable, writable));
    return CONFINED;
  }

  /**
   * Resolves paths as the file system names them: links and relative parts resolved where the path exists, and an
   * absolute path otherwise.
   *
   * @param paths the paths
   * @return the real paths
   */
  @VisibleForTesting
  static List<Path> realPaths(final List<Path> paths) {
    final List<Path> real = new ArrayList<>();
    for (final Path path : paths) {
      try {
        real.add(path.toRealPath());
      } catch (final IOException missing) {
        real.add(path.toAbsolutePath().normalize());
      }
    }
    return real;
  }

  /**
   * Builds the rules of a confinement: everything beneath the root may be read, except the hidden folders, in which
   * only the readable and the writable paths may be read; only the writable paths may be changed. A hidden folder
   * that is the root itself is not hidden, as the root holds the system.
   *
   * @param root     the folder everything else lies in
   * @param hidden   the folders that may not be read
   * @param readable the files and folders that may be read even in a hidden folder
   * @param writable the files and folders that may be changed
   * @return the rules
   * @throws IOException if a hidden folder cannot be resolved
   */
  @VisibleForTesting
  static List<Landlock.Rule> rules(final Path root, final List<Path> hidden, final List<Path> readable, final List<Path> writable)
    throws IOException {
    final List<Path> hiddenFolders = new ArrayList<>();
    for (final Path folder : hidden) {
      if (Files.exists(folder)) {
        // the walk below sees each folder by its real path
        final Path real = folder.toRealPath();
        if (!real.equals(root)) {
          hiddenFolders.add(real);
        }
      }
    }
    final List<Landlock.Rule> rules = new ArrayList<>();
    readAllBut(root, hiddenFolders, rules);
    for (final Path path : readable) {
      rules.add(new Landlock.Rule(path, false));
    }
    for (final Path path : writable) {
      rules.add(new Landlock.Rule(path, true));
    }
    return rules;
  }

  private static void readAllBut(final Path folder, final List<Path> hidden, final List<Landlock.Rule> rules) throws IOException {
    final List<Path> children;
    try (final Stream<Path> listing = Files.list(folder)) {
      children = listing.toList();
    } catch (final AccessDeniedException exception) {
      // what lies in a folder that cannot be listed is not read through it
      return;
    }
    for (final Path child : children) {
      // a link is read through its target, which a rule of its own covers unless it is hidden
      if (Files.isSymbolicLink(child) || hidden.contains(child)) {
        continue;
      }
      if (holdsAny(child, hidden)) {
        readAllBut(child, hidden, rules);
      } else {
        rules.add(new Landlock.Rule(child, false));
      }
    }
  }

  private static boolean holdsAny(final Path folder, final List<Path> hidden) {
    for (final Path path : hidden) {
      if (path.startsWith(folder)) {
        return true;
      }
    }
    return false;
  }

  private static Path parentOf(final Path path) {
    return Objects.requireNonNullElse(path.getParent(), path);
  }

  /**
   * Splits a list of paths such as a class path.
   *
   * @param list the list, or null
   * @return its paths
   */
  @VisibleForTesting
  static List<Path> pathsOf(final @Nullable String list) {
    final List<Path> paths = new ArrayList<>();
    if (list == null) {
      return paths;
    }
    for (final String entry : list.split(File.pathSeparator, -1)) {
      if (!entry.isEmpty()) {
        paths.add(Path.of(entry));
      }
    }
    return paths;
  }

  /**
   * Finds the jars of the Java agents of the helper, such as the coverage agent of the tests, whose classes may load
   * on a confined thread.
   *
   * @param arguments the options of the JVM
   * @return the jars
   */
  @VisibleForTesting
  static List<Path> agentsOf(final List<String> arguments) {
    final List<Path> jars = new ArrayList<>();
    for (final String argument : arguments) {
      if (argument.startsWith(AGENT_OPTION)) {
        final String rest = argument.substring(AGENT_OPTION.length());
        final int options = rest.indexOf('=');
        jars.add(Path.of(options < 0 ? rest : rest.substring(0, options)));
      }
    }
    return jars;
  }

  /**
   * Finds the files the symbolic links in folders point to, such as the libraries the server lacks, which the
   * session links to.
   *
   * @param folders the folders
   * @return the files the links point to
   * @throws IOException if a folder cannot be listed
   */
  @VisibleForTesting
  static List<Path> linkedFiles(final List<Path> folders) throws IOException {
    final List<Path> targets = new ArrayList<>();
    for (final Path folder : folders) {
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try (final Stream<Path> listing = Files.list(folder)) {
        for (final Path entry : listing.toList()) {
          if (Files.isSymbolicLink(entry)) {
            final Path parent = parentOf(entry);
            targets.add(parent.resolve(Files.readSymbolicLink(entry)));
          }
        }
      }
    }
    return targets;
  }
}
