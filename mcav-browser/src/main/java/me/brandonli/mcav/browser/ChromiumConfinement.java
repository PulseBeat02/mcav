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

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;

/*
 * JCEF always turns Chromium's own sandbox off, so an exploited renderer would run as the server's user. Chromium may
 * therefore not read the server's, home and temporary folders beyond what it needs, and changes only its session. On
 * Linux Landlock confines the starting thread and what it starts; on macOS Seatbelt confines the whole helper.
 */
final class ChromiumConfinement {

  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  static final String CONFINED =
    "Chromium runs confined: it cannot read the server's folder, the home folder or the temporary folder, and it writes only into the folder of its session.";
  static final String NOT_ASKED = "Chromium runs without confinement, as the options of the browser allow.";
  static final String NOT_SUPPORTED = "Because MCAV confines it on Linux and macOS only, Chromium runs without confinement.";
  private static final String UNAVAILABLE = "Because %s, Chromium runs without confinement.";

  private static final Path ROOT = Path.of("/");
  private static final Path DEVICES = Path.of("/dev");
  private static final Path PROCESSES = Path.of("/proc");

  private static final String AGENT_OPTION = "-javaagent:";
  private static final char AGENT_OPTIONS_SEPARATOR = '=';

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final String LIBRARY_PATH_VARIABLE = "LD_LIBRARY_PATH";

  private ChromiumConfinement() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static String confine(final HelperConfiguration configuration, final OperatingSystem system) throws IOException {
    return confine(configuration, system, Landlock::version, Seatbelt.ofSystem());
  }

  @VisibleForTesting
  static String confine(
    final HelperConfiguration configuration,
    final OperatingSystem system,
    final IntSupplier version,
    final Seatbelt seatbelt
  ) throws IOException {
    if (!configuration.isConfined()) {
      return NOT_ASKED;
    }

    final Path socket = configuration.getSocket();
    final Path session = parentOf(socket);
    final Path home = Path.of(requireNonNull(System.getProperty(USER_HOME_PROPERTY))); // checker framework
    final Path serverFolder = configuration.getServerFolder();
    final List<Path> hidden = List.of(serverFolder, home, parentOf(session));
    final List<Path> readable = readablePaths(configuration);

    if (system == OperatingSystem.MAC) {
      return confineOnMac(seatbelt, hidden, readable, session);
    }

    if (system != OperatingSystem.LINUX) {
      return NOT_SUPPORTED;
    }

    return confineOnLinux(version, hidden, readable, session);
  }

  private static List<Path> readablePaths(final HelperConfiguration configuration) {
    final List<Path> readable = new ArrayList<>();
    final String javaHome = requireNonNull(System.getProperty(JAVA_HOME_PROPERTY)); // checker framework
    final Path java = Path.of(javaHome);
    readable.add(java);
    final Path natives = configuration.getNatives();
    readable.add(natives);
    final String classPath = System.getProperty(CLASS_PATH_PROPERTY);
    final List<Path> classes = pathsOf(classPath);
    readable.addAll(classes);
    final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    final List<String> inputArguments = runtime.getInputArguments();
    final List<Path> agents = agentsOf(inputArguments);
    readable.addAll(agents);

    return readable;
  }

  private static String confineOnMac(final Seatbelt seatbelt, final List<Path> hidden, final List<Path> readable, final Path session)
    throws IOException {
    if (!seatbelt.isAvailable()) {
      return UNAVAILABLE.formatted(Seatbelt.MISSING);
    }

    // Seatbelt matches the paths files really have, such as /private/var for /var
    final List<Path> realHidden = realPaths(hidden);
    final List<Path> realReadable = realPaths(readable);
    final List<Path> writable = List.of(session, DEVICES);
    final List<Path> realWritable = realPaths(writable);
    final String profile = Seatbelt.profile(realHidden, realReadable, realWritable);
    seatbelt.restrictProcess(profile);

    return CONFINED;
  }

  private static String confineOnLinux(final IntSupplier version, final List<Path> hidden, final List<Path> readable, final Path session)
    throws IOException {
    final int available = version.getAsInt();
    if (available < 1) {
      final String missing = Landlock.describeMissing(available);
      return UNAVAILABLE.formatted(missing);
    }

    final String libraryPath = System.getenv(LIBRARY_PATH_VARIABLE);
    final List<Path> libraryFolders = pathsOf(libraryPath);
    final List<Path> libraries = linkedFiles(libraryFolders);
    readable.addAll(libraries);
    final List<Path> writable = List.of(session, DEVICES, PROCESSES);
    final List<Landlock.Rule> restrictions = rules(ROOT, hidden, readable, writable);
    Landlock.restrictThread(available, restrictions);

    return CONFINED;
  }

  @VisibleForTesting
  static List<Path> realPaths(final List<Path> paths) {
    final List<Path> real = new ArrayList<>();
    for (final Path path : paths) {
      try {
        real.add(path.toRealPath());
      } catch (final IOException exception) {
        real.add(path.toAbsolutePath().normalize());
      }
    }

    return real;
  }

  // a hidden folder that is the root is not hidden, as the root holds the system
  @VisibleForTesting
  static List<Landlock.Rule> rules(final Path root, final List<Path> hidden, final List<Path> readable, final List<Path> writable)
    throws IOException {
    final List<Path> hiddenFolders = new ArrayList<>();
    for (final Path folder : hidden) {
      if (!Files.exists(folder)) {
        continue;
      }
      final Path real = folder.toRealPath();
      if (!real.equals(root)) {
        hiddenFolders.add(real);
      }
    }

    final List<Landlock.Rule> rules = new ArrayList<>();
    allowReadingAllBut(root, hiddenFolders, rules);
    for (final Path path : readable) {
      rules.add(new Landlock.Rule(path, Landlock.Access.READ_ONLY));
    }

    for (final Path path : writable) {
      rules.add(new Landlock.Rule(path, Landlock.Access.READ_WRITE));
    }

    return rules;
  }

  private static void allowReadingAllBut(final Path folder, final List<Path> hidden, final List<Landlock.Rule> rules) throws IOException {
    final List<Path> children;
    try (final Stream<Path> listing = Files.list(folder)) {
      children = listing.toList();
    } catch (final AccessDeniedException exception) {
      return;
    }

    for (final Path child : children) {
      if (Files.isSymbolicLink(child) || hidden.contains(child)) {
        continue;
      }
      if (holdsAny(child, hidden)) {
        allowReadingAllBut(child, hidden, rules);
      } else {
        rules.add(new Landlock.Rule(child, Landlock.Access.READ_ONLY));
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

  @VisibleForTesting
  static List<Path> pathsOf(final @Nullable String list) {
    final List<Path> paths = new ArrayList<>();
    if (list == null) {
      return paths;
    }

    for (final String entry : list.split(File.pathSeparator, KEEP_TRAILING_EMPTY_PARTS)) {
      if (!entry.isEmpty()) {
        paths.add(Path.of(entry));
      }
    }

    return paths;
  }

  // the classes of an agent, such as the coverage agent of the tests, may load on a confined thread
  @VisibleForTesting
  static List<Path> agentsOf(final List<String> arguments) {
    final List<Path> jars = new ArrayList<>();
    for (final String argument : arguments) {
      if (argument.startsWith(AGENT_OPTION)) {
        final String rest = argument.substring(AGENT_OPTION.length());
        final int options = rest.indexOf(AGENT_OPTIONS_SEPARATOR);
        jars.add(Path.of(options < 0 ? rest : rest.substring(0, options)));
      }
    }

    return jars;
  }

  @VisibleForTesting
  static List<Path> linkedFiles(final List<Path> folders) throws IOException {
    final List<Path> targets = new ArrayList<>();
    for (final Path folder : folders) {
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try (final Stream<Path> listing = Files.list(folder)) {
        final List<Path> entries = listing.toList();
        addLinkTargets(entries, targets);
      }
    }

    return targets;
  }

  private static void addLinkTargets(final List<Path> entries, final List<Path> targets) throws IOException {
    for (final Path entry : entries) {
      if (Files.isSymbolicLink(entry)) {
        final Path parent = parentOf(entry);
        targets.add(parent.resolve(Files.readSymbolicLink(entry)));
      }
    }
  }
}
