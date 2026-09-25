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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Knows how to start a browser helper process: the Java program of the server, the class path of the helper, the
 * options of its JVM, the environment it keeps, and on Linux the Xvfb program its display comes from.
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
  private final @Nullable String path;
  private final Map<String, String> environment;
  private final long startTimeoutMillis;

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
    this.java = java;
    this.mainClass = mainClass;
    this.classPath = List.copyOf(classPath);
    this.extraJvmOptions = List.copyOf(extraJvmOptions);
    this.os = os;
    this.path = environment.get("PATH");
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
   * Builds the environment of a helper: the kept variables of the server, and on Linux the display.
   *
   * @param display the Xvfb display on Linux, or null elsewhere
   * @return the environment
   */
  Map<String, String> createEnvironment(final @Nullable XvfbDisplay display) {
    final Map<String, String> kept = HelperEnvironment.filter(this.environment);
    if (display != null) {
      final String displayName = display.getDisplay();
      final Path authority = display.getAuthority();
      kept.put("DISPLAY", displayName);
      kept.put("XAUTHORITY", authority.toString());
    }
    return kept;
  }

  /**
   * Finds the Xvfb program on Linux, where CEF needs an X display.
   *
   * @return the program, or empty on other systems
   * @throws PlayerException on Linux without Xvfb
   */
  Optional<Path> findXvfb() {
    if (this.os != OS.LINUX) {
      return Optional.empty();
    }
    final Optional<Path> found = XvfbDisplay.find(this.path);
    if (found.isEmpty()) {
      throw new PlayerException(
        "The browser needs Xvfb on Linux, which is not installed; install the package xvfb (Debian, Ubuntu) or xorg-x11-server-Xvfb (Fedora, RHEL)"
      );
    }
    return found;
  }

  long getStartTimeoutMillis() {
    return this.startTimeoutMillis;
  }

  OS getOs() {
    return this.os;
  }
}
