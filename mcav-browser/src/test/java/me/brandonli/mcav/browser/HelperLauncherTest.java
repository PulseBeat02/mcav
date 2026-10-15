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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.utils.os.Os;
import org.cef.CefApp;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelperLauncherTest {

  private static final Path JAVA = Path.of("/opt/java/bin/java");
  private static final List<Path> CLASS_PATH = List.of(Path.of("/libs/mcav-browser.jar"), Path.of("/libs/jcef-api.jar"));
  private static final String BROWSER_PACKAGE = "me/brandonli/mcav/browser/";
  private static final Pattern DESCRIBED_TYPE = Pattern.compile("L([^;<]+)[;<]");

  private static final Path SESSION_FOLDER = Path.of("/tmp/mcav-browser-1");
  private static final Path OTHER_SESSION_FOLDER = Path.of("/tmp/x");

  @TempDir
  Path folder;

  private HelperLauncher launcher(final Os os, final Map<String, String> environment) {
    return new HelperLauncher(JAVA, HelperLauncher.MAIN_CLASS, CLASS_PATH, List.of("-javaagent:/agent.jar"), os, environment, 5_000L);
  }

  @Test
  void shouldRunTheHelperJvmHeadlessAllowNativesAndKeepItsFilesInTheSession() {
    final Path session = SESSION_FOLDER;
    final List<String> command = this.launcher(Os.LINUX, Map.of()).createCommand(session);
    final String javaProgram = command.getFirst();
    assertEquals(JAVA.toString(), javaProgram);
    final boolean runsHeadless = command.contains("-Djava.awt.headless=true");
    assertTrue(runsHeadless);
    final boolean nativeAccessEnabled = command.contains("--enable-native-access=ALL-UNNAMED");
    assertTrue(nativeAccessEnabled);
    final boolean attachDisabled = command.contains("-XX:+DisableAttachMechanism");
    assertTrue(attachDisabled);
    final boolean temporaryFolderInTheSession = command.contains("-Djava.io.tmpdir=" + session);
    assertTrue(temporaryFolderInTheSession);
    final boolean agentPassedOn = command.contains("-javaagent:/agent.jar");
    assertTrue(agentPassedOn);
    final int classPath = command.indexOf("-cp");
    final String joinedClassPath = command.get(classPath + 1);
    assertEquals(Path.of("/libs/mcav-browser.jar") + File.pathSeparator + Path.of("/libs/jcef-api.jar"), joinedClassPath);
    final String mainClass = command.getLast();
    assertEquals(HelperLauncher.MAIN_CLASS, mainClass);
    assertEquals(BrowserHelper.class.getName(), HelperLauncher.MAIN_CLASS);
  }

  @Test
  void shouldKeepLittleOfTheEnvironmentAndNeverTheDisplayOfTheServer() throws IOException {
    final Map<String, String> server = Map.of(
      "PATH",
      "/usr/bin",
      "DISCORD_TOKEN",
      "secret",
      "DISPLAY",
      ":0",
      "LD_LIBRARY_PATH",
      "/opt/x",
      "TMPDIR",
      "/var/tmp"
    );
    final HelperLauncher linux = this.launcher(Os.LINUX, server);
    final Path authority = this.folder.resolve(NullDisplay.AUTHORITY_FILE);
    final String session = this.folder.toString();
    final Map<String, String> linuxEnvironment = linux.createEnvironment(this.folder, null);
    assertEquals(
      Map.of("PATH", "/usr/bin", "XAUTHORITY", authority.toString(), "TMPDIR", session, "XDG_DATA_HOME", session),
      linuxEnvironment
    );
    final Path libraries = this.folder.resolve("lib");
    final Map<String, String> linuxEnvironmentWithLibraries = linux.createEnvironment(this.folder, libraries);
    assertEquals(
      Map.of(
        "PATH",
        "/usr/bin",
        "XAUTHORITY",
        authority.toString(),
        "TMPDIR",
        session,
        "XDG_DATA_HOME",
        session,
        "LD_LIBRARY_PATH",
        libraries.toString()
      ),
      linuxEnvironmentWithLibraries
    );
    final Map<String, String> macEnvironment = this.launcher(Os.MAC, server).createEnvironment(this.folder, null);
    assertEquals(Map.of("PATH", "/usr/bin", "TMPDIR", session, "MAC_CHROMIUM_TMPDIR", session), macEnvironment);
    final Map<String, String> windowsEnvironment = this.launcher(Os.WINDOWS, server).createEnvironment(this.folder, null);
    assertEquals(Map.of("PATH", "/usr/bin", "TMPDIR", "/var/tmp"), windowsEnvironment);
    final Os operatingSystem = linux.getOs();
    assertEquals(Os.LINUX, operatingSystem);
    final long startTimeoutMilliseconds = linux.getStartTimeoutMilliseconds();
    assertEquals(5_000L, startTimeoutMilliseconds);
  }

  @Test
  void shouldLinkTheLibrariesOfALauncherIntoEverySession() throws IOException {
    final HelperLauncher plain = this.launcher(Os.LINUX, Map.of());
    final @Nullable Path plainLibraries = plain.linkLibraries(this.folder);
    assertNull(plainLibraries);
    final Path linked = this.folder.resolve("lib");
    final HelperLauncher withLibraries = plain.withLibraries(session -> Files.createDirectory(session.resolve("lib")));
    final @Nullable Path linkedLibraries = withLibraries.linkLibraries(this.folder);
    assertEquals(linked, linkedLibraries);
    final boolean linkedFolderExists = Files.isDirectory(linked);
    assertTrue(linkedFolderExists);
    final List<String> commandWithLibraries = withLibraries.createCommand(this.folder);
    assertEquals(plain.createCommand(this.folder), commandWithLibraries);
    final long startTimeoutMilliseconds = withLibraries.getStartTimeoutMilliseconds();
    assertEquals(plain.getStartTimeoutMilliseconds(), startTimeoutMilliseconds);
    final Os operatingSystem = withLibraries.getOs();
    assertEquals(Os.LINUX, operatingSystem);
  }

  @Test
  void shouldLocateClassesByWhereTheyWereLoadedFrom() {
    final Path jcef = HelperLauncher.locate(CefApp.class);
    final boolean namedJcefApi = jcef.getFileName().toString().startsWith("jcef-api");
    assertTrue(namedJcefApi, jcef.toString());
    final Path browser = HelperLauncher.locate(BrowserHelper.class);
    final boolean browserLocationExists = Files.exists(browser);
    assertTrue(browserLocationExists, browser.toString());
    final BrowserException bootstrap = assertThrows(BrowserException.class, () -> HelperLauncher.locate(String.class));
    final boolean namesTheClass = bootstrap.getMessage().contains("java.lang.String");
    assertTrue(namesTheClass);
  }

  @Test
  void shouldNotAcceptAClassLoadedFromTheNetworkAsAHelperClassPath() throws Exception {
    final String name = Loaded.class.getName();
    final byte[] bytes;
    try (final InputStream input = Loaded.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
      bytes = input.readAllBytes();
    }
    final URL remote = URI.create("http://example.com/remote.jar").toURL();
    final CodeSource source = new CodeSource(remote, (Certificate[]) null);
    final ProtectionDomain domain = new ProtectionDomain(source, null);
    final ClassLoader loader = new ClassLoader(null) {
      @Override
      protected Class<?> findClass(final String className) throws ClassNotFoundException {
        if (!className.equals(name)) {
          throw new ClassNotFoundException(className);
        }

        return this.defineClass(className, bytes, 0, bytes.length, domain);
      }
    };
    final Class<?> remoteClass = loader.loadClass(name);
    final BrowserException failure = assertThrows(BrowserException.class, () -> HelperLauncher.locate(remoteClass));
    final boolean namesTheRemoteJar = failure.getMessage().contains("http://example.com/remote.jar");
    assertTrue(namesTheRemoteJar, failure.getMessage());
  }

  @Test
  void shouldNameTheJvmProgramForItsSystem() {
    final String windowsExecutable = HelperLauncher.javaExecutable(Os.WINDOWS);
    assertEquals("java.exe", windowsExecutable);
    final String linuxExecutable = HelperLauncher.javaExecutable(Os.LINUX);
    assertEquals("java", linuxExecutable);
    final String macExecutable = HelperLauncher.javaExecutable(Os.MAC);
    assertEquals("java", macExecutable);
  }

  @Test
  void shouldUseThisJavaAndTheClassPathOfTheModuleByDefault() {
    final HelperLauncher launcher = HelperLauncher.createDefault(1_000L, List.of());
    final List<String> command = launcher.createCommand(OTHER_SESSION_FOLDER);
    final boolean runsThisJava = command.getFirst().startsWith(System.getProperty("java.home"));
    assertTrue(runsThisJava, command.getFirst());
    final String classPath = command.get(command.indexOf("-cp") + 1);
    final boolean holdsJcefApi = classPath.contains("jcef-api");
    assertTrue(holdsJcefApi, classPath);
    final boolean holdsJcefMaven = classPath.contains("jcefmaven");
    assertTrue(holdsJcefMaven, classPath);
    final boolean holdsJogl = classPath.contains("jogl");
    assertFalse(holdsJogl, classPath);
    final long startTimeoutMilliseconds = launcher.getStartTimeoutMilliseconds();
    assertEquals(1_000L, startTimeoutMilliseconds);
  }

  @Test
  void shouldReachNoClassBeyondTheClassPathOfTheHelper() throws IOException {
    // the helper runs with the browser module, JCEF and the JDK only: a class of another module that it reaches, such
    // as mcav-common's, fails there with a NoClassDefFoundError that no test JVM shows, which all have every module
    final Set<String> reached = new HashSet<>();
    final Deque<String> pending = new ArrayDeque<>(List.of(HelperLauncher.MAIN_CLASS.replace('.', '/')));
    final List<String> outside = new ArrayList<>();
    while (!pending.isEmpty()) {
      final String name = pending.pop();
      if (!reached.add(name)) {
        continue;
      }
      this.followUsedClasses(name, pending, outside);
    }
    assertEquals(List.of(), outside);
    final boolean reachedNullDisplay = reached.contains(BROWSER_PACKAGE + "NullDisplay");
    assertTrue(reachedNullDisplay, reached::toString);
  }

  private void followUsedClasses(final String name, final Deque<String> pending, final List<String> outside) throws IOException {
    for (final String used : this.usedClasses(name)) {
      if (used.startsWith(BROWSER_PACKAGE)) {
        pending.push(used);
      } else if (!this.isOnTheHelperClassPath(used)) {
        outside.add(name + " uses " + used);
      }
    }
  }

  private Set<String> usedClasses(final String name) throws IOException {
    final byte[] bytes;
    try (final InputStream in = HelperLauncherTest.class.getResourceAsStream("/" + name + ".class")) {
      assertNotNull(in, name);
      bytes = in.readAllBytes();
    }
    final ClassModel model = ClassFile.of().parse(bytes);
    final Set<String> used = new TreeSet<>();
    for (final PoolEntry entry : model.constantPool()) {
      if (entry instanceof final ClassEntry type) {
        this.addClass(type, used);
      } else if (entry instanceof final NameAndTypeEntry member) {
        this.addDescribed(member.type().stringValue(), used);
      } else if (entry instanceof final MethodTypeEntry method) {
        this.addDescribed(method.descriptor().stringValue(), used);
      }
    }
    model.fields().forEach(field -> this.addDescribed(field.fieldType().stringValue(), used));
    model.methods().forEach(method -> this.addDescribed(method.methodType().stringValue(), used));

    return used;
  }

  private void addClass(final ClassEntry type, final Set<String> used) {
    final String internal = type.asInternalName();
    if (internal.startsWith("[")) {
      this.addDescribed(internal, used);
    } else {
      used.add(internal);
    }
  }

  private void addDescribed(final String descriptor, final Set<String> used) {
    final Matcher matcher = DESCRIBED_TYPE.matcher(descriptor);
    while (matcher.find()) {
      used.add(matcher.group(1));
    }
  }

  private boolean isOnTheHelperClassPath(final String name) {
    return (
      name.startsWith("java/") ||
      name.startsWith("javax/") ||
      name.startsWith("jdk/") ||
      name.startsWith("org/cef/") ||
      name.startsWith("me/friwi/jcefmaven/")
    );
  }

  // A class for shouldNotAcceptAClassLoadedFromTheNetworkAsAHelperClassPath() to load again.
  static final class Loaded {}
}
