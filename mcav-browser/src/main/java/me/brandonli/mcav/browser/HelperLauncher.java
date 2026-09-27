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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Knows how to start a browser helper process: the Java program of the server, the class path of the helper, the
 * options of its JVM, and the environment it keeps, which on Linux also names the authority file of its null display
 * and the folder of the libraries the server lacks.
 *
 * <p>The class path is taken from where the classes of mcav's browser module, JCEF and jcefmaven were loaded from,
 * which works whether they are separate jars, as Paper's library loader gives them, or shaded into one plugin jar.
 * Relocating the {@code org.cef} or {@code me.friwi} packages breaks JCEF's native methods and is not supported. The
 * helper JVM gets the options CEF needs, which the server itself never does: it is headless, may load native code,
 * uses a small heap, and keeps its temporary files in the private folder of its session.
 */
final class HelperLauncher {

  /**
   * The main class of the helper.
   */
  static final String MAIN_CLASS = "me.brandonli.mcav.browser.BrowserHelper";

  private final Path java;
  private final String mainClass;
  private final List<Path> classPath;
  private final List<String> extraJvmOptions;
  private final OS os;
  private final Map<String, String> environment;
  private final long startTimeoutMillis;
  private final @Nullable LibraryLinker libraries;

  /**
   * Constructs a launcher.
   *
   * @param java               the Java program
   * @param mainClass          the main class of the helper
   * @param classPath          the class path of the helper
   * @param extraJvmOptions    options added to the JVM of the helper, such as a coverage agent in tests
   * @param os                 the operating system
   * @param environment        the environment of the server
   * @param startTimeoutMillis how long a helper may take until the page shows
   */
  @VisibleForTesting
  HelperLauncher(
    final Path java,
    final String mainClass,
    final List<Path> classPath,
    final List<String> extraJvmOptions,
    final OS os,
    final Map<String, String> environment,
    final long startTimeoutMillis
  ) {
    this(java, mainClass, classPath, extraJvmOptions, os, environment, startTimeoutMillis, null);
  }

  private HelperLauncher(
    final Path java,
    final String mainClass,
    final List<Path> classPath,
    final List<String> extraJvmOptions,
    final OS os,
    final Map<String, String> environment,
    final long startTimeoutMillis,
    final @Nullable LibraryLinker libraries
  ) {
    this.libraries = libraries;
    this.java = java;
    this.mainClass = mainClass;
    this.classPath = List.copyOf(classPath);
    this.extraJvmOptions = List.copyOf(extraJvmOptions);
    this.os = os;
    this.environment = Map.copyOf(environment);
    this.startTimeoutMillis = startTimeoutMillis;
  }

  /**
   * Gets the name of the program of a JVM on an operating system.
   *
   * @param os the operating system
   * @return {@code java.exe} on Windows, {@code java} elsewhere
   */
  static String javaExecutable(final OS os) {
    return os == OS.WINDOWS ? "java.exe" : "java";
  }

  /**
   * Creates the launcher of this JVM: its Java program, the class path the browser module was loaded from, and the
   * environment of the server.
   *
   * @param startTimeoutMillis how long a helper may take until the page shows
   * @param extraJvmOptions    options added to the JVM of the helper
   * @return the launcher
   * @throws PlayerException if a class the helper needs was not loaded from a file
   */
  static HelperLauncher createDefault(final long startTimeoutMillis, final List<String> extraJvmOptions) {
    final String javaHome = System.getProperty("java.home");
    final OS os = OSUtils.getOS();
    final Path java = Path.of(javaHome, "bin", javaExecutable(os));
    final Set<Path> entries = new LinkedHashSet<>();
    entries.add(locate(BrowserHelper.class));
    entries.add(locate(CefApp.class));
    entries.add(locate(CefAppBuilder.class));
    final List<Path> classPath = new ArrayList<>(entries);
    final Map<String, String> environment = System.getenv();
    return new HelperLauncher(java, MAIN_CLASS, classPath, extraJvmOptions, os, environment, startTimeoutMillis);
  }

  /**
   * Finds the jar or folder a class was loaded from.
   *
   * @param type the class
   * @return the jar or folder
   * @throws PlayerException if the class was not loaded from a file
   */
  @VisibleForTesting
  static Path locate(final Class<?> type) {
    final ProtectionDomain domain = type.getProtectionDomain();
    final CodeSource source = domain.getCodeSource();
    final URL location = source == null ? null : source.getLocation();
    if (location == null) {
      throw new PlayerException("The browser helper cannot find where " + type.getName() + " was loaded from");
    }
    try {
      return Path.of(location.toURI());
    } catch (final URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException exception) {
      throw new PlayerException("The browser helper cannot use " + location + " as its class path", exception);
    }
  }

  /**
   * Builds the command line of a helper.
   *
   * @param folder the private folder of the session, where the helper keeps its temporary files
   * @return the command line
   */
  List<String> createCommand(final Path folder) {
    final List<String> command = new ArrayList<>();
    command.add(this.java.toString());
    command.add("-Djava.awt.headless=true");
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-Xmx512m");
    command.add("-XX:+UseSerialGC");
    command.add("-XX:+DisableAttachMechanism");
    command.add("-Dfile.encoding=UTF-8");
    command.add("-Djava.io.tmpdir=" + folder);
    command.addAll(this.extraJvmOptions);
    command.add("-cp");
    final List<String> entries = new ArrayList<>();
    for (final Path entry : this.classPath) {
      entries.add(entry.toString());
    }
    command.add(String.join(File.pathSeparator, entries));
    command.add(this.mainClass);
    return command;
  }

  /**
   * Builds the environment of a helper: the kept variables of the server, and on Linux the authority file of the
   * helper's null display, which X clients read, and the libraries the server lacks. No display of the server is
   * passed on.
   *
   * @param folder    the private folder of the session
   * @param libraries the folder of the libraries the server lacks on Linux, or null
   * @return the environment
   */
  Map<String, String> createEnvironment(final Path folder, final @Nullable Path libraries) {
    final Map<String, String> kept = HelperEnvironment.filter(this.environment);
    if (this.os == OS.LINUX) {
      final Path authority = authorityOf(folder);
      kept.put("XAUTHORITY", authority.toString());
    }
    if (libraries != null) {
      kept.put("LD_LIBRARY_PATH", libraries.toString());
    }
    return kept;
  }

  /**
   * Gets the X authority file of a session, which the helper writes and its X clients read.
   *
   * @param folder the folder of the session
   * @return the file
   */
  static Path authorityOf(final Path folder) {
    return folder.resolve(NullDisplay.AUTHORITY_FILE);
  }

  /**
   * Creates a launcher like this one whose helpers also get the libraries the server lacks.
   *
   * @param linker links the libraries into the folder of a session
   * @return the launcher
   */
  HelperLauncher withLibraries(final LibraryLinker linker) {
    return new HelperLauncher(
      this.java,
      this.mainClass,
      this.classPath,
      this.extraJvmOptions,
      this.os,
      this.environment,
      this.startTimeoutMillis,
      linker
    );
  }

  /**
   * Links the libraries the server lacks into the folder of a session, if this launcher brings any.
   *
   * @param folder the folder of the session
   * @return the folder of the libraries, or null if this launcher brings none
   * @throws IOException if they cannot be linked
   */
  @Nullable Path linkLibraries(final Path folder) throws IOException {
    final LibraryLinker linker = this.libraries;
    return linker == null ? null : linker.link(folder);
  }

  long getStartTimeoutMillis() {
    return this.startTimeoutMillis;
  }

  OS getOs() {
    return this.os;
  }

  /**
   * Links the libraries a server lacks into a folder of a session.
   */
  @FunctionalInterface
  interface LibraryLinker {
    /**
     * Links the libraries.
     *
     * @param session the folder of the session
     * @return the folder of the links, which becomes the helper's library path
     * @throws IOException if a link cannot be created
     */
    Path link(Path session) throws IOException;
  }
}
