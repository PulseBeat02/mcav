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
import java.nio.file.Files;
import java.nio.file.Path;
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
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import org.cef.CefApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelperLauncherTest {

  private static final Path JAVA = Path.of("/opt/java/bin/java");
  private static final List<Path> CLASS_PATH = List.of(Path.of("/libs/mcav-browser.jar"), Path.of("/libs/jcef-api.jar"));
  private static final String BROWSER_PACKAGE = "me/brandonli/mcav/browser/";
  // the class in a descriptor: Lme/brandonli/mcav/Foo; (arrays and generics add nothing a class entry lacks)
  private static final Pattern DESCRIBED_TYPE = Pattern.compile("L([^;<]+)[;<]");

  @TempDir
  Path folder;

  private static HelperLauncher launcher(final OS os, final Map<String, String> environment) {
    return new HelperLauncher(JAVA, HelperLauncher.MAIN_CLASS, CLASS_PATH, List.of("-javaagent:/agent.jar"), os, environment, 5_000L);
  }

  @Test
  void theHelperJvmIsHeadlessMayLoadNativesAndKeepsItsFilesInTheSession() {
    final Path session = Path.of("/tmp/mcav-browser-1");
    final List<String> command = launcher(OS.LINUX, Map.of()).createCommand(session);
    assertEquals(JAVA.toString(), command.getFirst());
    assertTrue(command.contains("-Djava.awt.headless=true"));
    assertTrue(command.contains("--enable-native-access=ALL-UNNAMED"));
    assertTrue(command.contains("-XX:+DisableAttachMechanism"));
    assertTrue(command.contains("-Djava.io.tmpdir=" + session));
    assertTrue(command.contains("-javaagent:/agent.jar"));
    final int classPath = command.indexOf("-cp");
    assertEquals(Path.of("/libs/mcav-browser.jar") + File.pathSeparator + Path.of("/libs/jcef-api.jar"), command.get(classPath + 1));
    assertEquals(HelperLauncher.MAIN_CLASS, command.getLast());
    assertEquals(BrowserHelper.class.getName(), HelperLauncher.MAIN_CLASS);
  }

  @Test
  void theEnvironmentKeepsLittleAndNeverTheDisplayOfTheServer() throws IOException {
    final Map<String, String> server = Map.of("PATH", "/usr/bin", "DISCORD_TOKEN", "secret", "DISPLAY", ":0", "LD_LIBRARY_PATH", "/opt/x");
    final HelperLauncher linux = launcher(OS.LINUX, server);
    final Path authority = this.folder.resolve(NullDisplay.AUTHORITY_FILE);
    assertEquals(Map.of("PATH", "/usr/bin", "XAUTHORITY", authority.toString()), linux.createEnvironment(this.folder, null));
    final Path libraries = this.folder.resolve("lib");
    assertEquals(
      Map.of("PATH", "/usr/bin", "XAUTHORITY", authority.toString(), "LD_LIBRARY_PATH", libraries.toString()),
      linux.createEnvironment(this.folder, libraries)
    );
    assertEquals(Map.of("PATH", "/usr/bin"), launcher(OS.WINDOWS, server).createEnvironment(this.folder, null));
    assertEquals(OS.LINUX, linux.getOs());
    assertEquals(5_000L, linux.getStartTimeoutMillis());
  }

  @Test
  void aLauncherWithLibrariesLinksThemIntoEverySession() throws IOException {
    final HelperLauncher plain = launcher(OS.LINUX, Map.of());
    assertNull(plain.linkLibraries(this.folder));
    final Path linked = this.folder.resolve("lib");
    final HelperLauncher withLibraries = plain.withLibraries(session -> Files.createDirectory(session.resolve("lib")));
    assertEquals(linked, withLibraries.linkLibraries(this.folder));
    assertTrue(Files.isDirectory(linked));
    assertEquals(plain.createCommand(this.folder), withLibraries.createCommand(this.folder));
    assertEquals(plain.getStartTimeoutMillis(), withLibraries.getStartTimeoutMillis());
    assertEquals(OS.LINUX, withLibraries.getOs());
  }

  @Test
  void classesAreLocatedByWhereTheyWereLoadedFrom() {
    final Path jcef = HelperLauncher.locate(CefApp.class);
    assertTrue(jcef.getFileName().toString().startsWith("jcef-api"), jcef.toString());
    final Path browser = HelperLauncher.locate(BrowserHelper.class);
    assertTrue(Files.exists(browser), browser.toString());
    final PlayerException bootstrap = assertThrows(PlayerException.class, () -> HelperLauncher.locate(String.class));
    assertTrue(bootstrap.getMessage().contains("java.lang.String"));
  }

  /**
   * A class for {@link #aClassLoadedFromTheNetworkCannotBeAHelperClassPath()} to load again.
   */
  static final class Loaded {}

  @Test
  void aClassLoadedFromTheNetworkCannotBeAHelperClassPath() throws Exception {
    final String name = Loaded.class.getName();
    final byte[] bytes;
    try (final java.io.InputStream in = Loaded.class.getResourceAsStream("HelperLauncherTest$Loaded.class")) {
      bytes = in.readAllBytes();
    }
    final java.net.URL remote = java.net.URI.create("http://example.com/remote.jar").toURL();
    final java.security.CodeSource source = new java.security.CodeSource(remote, (java.security.cert.Certificate[]) null);
    final java.security.ProtectionDomain domain = new java.security.ProtectionDomain(source, null);
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
    final PlayerException failure = assertThrows(PlayerException.class, () -> HelperLauncher.locate(remoteClass));
    assertTrue(failure.getMessage().contains("http://example.com/remote.jar"), failure.getMessage());
  }

  @Test
  void theJvmProgramIsNamedForItsSystem() {
    assertEquals("java.exe", HelperLauncher.javaExecutable(OS.WINDOWS));
    assertEquals("java", HelperLauncher.javaExecutable(OS.LINUX));
    assertEquals("java", HelperLauncher.javaExecutable(OS.MAC));
  }

  @Test
  void theDefaultLauncherUsesThisJavaAndTheModulesClassPath() {
    final HelperLauncher launcher = HelperLauncher.createDefault(1_000L, List.of());
    final List<String> command = launcher.createCommand(Path.of("/tmp/x"));
    assertTrue(command.getFirst().startsWith(System.getProperty("java.home")), command.getFirst());
    final String classPath = command.get(command.indexOf("-cp") + 1);
    assertTrue(classPath.contains("jcef-api"), classPath);
    assertTrue(classPath.contains("jcefmaven"), classPath);
    assertFalse(classPath.contains("jogl"), classPath);
    assertEquals(1_000L, launcher.getStartTimeoutMillis());
  }

  @Test
  void theHelperReachesNoClassBeyondItsClassPath() throws IOException {
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
      for (final String used : usedClasses(name)) {
        if (used.startsWith(BROWSER_PACKAGE)) {
          pending.push(used);
        } else if (!isOnTheHelperClassPath(used)) {
          outside.add(name + " uses " + used);
        }
      }
    }
    assertEquals(List.of(), outside);
    assertTrue(reached.contains(BROWSER_PACKAGE + "NullDisplay"), reached::toString);
  }

  private static Set<String> usedClasses(final String name) throws IOException {
    final byte[] bytes;
    try (InputStream in = HelperLauncherTest.class.getResourceAsStream("/" + name + ".class")) {
      assertNotNull(in, name);
      bytes = in.readAllBytes();
    }
    final ClassModel model = ClassFile.of().parse(bytes);
    final Set<String> used = new TreeSet<>();
    for (final PoolEntry entry : model.constantPool()) {
      if (entry instanceof final ClassEntry type) {
        final String internal = type.asInternalName();
        if (internal.startsWith("[")) {
          addDescribed(internal, used);
        } else {
          used.add(internal);
        }
      } else if (entry instanceof final NameAndTypeEntry member) {
        addDescribed(member.type().stringValue(), used);
      } else if (entry instanceof final MethodTypeEntry method) {
        addDescribed(method.descriptor().stringValue(), used);
      }
    }
    model.fields().forEach(field -> addDescribed(field.fieldType().stringValue(), used));
    model.methods().forEach(method -> addDescribed(method.methodType().stringValue(), used));
    return used;
  }

  private static void addDescribed(final String descriptor, final Set<String> used) {
    final Matcher matcher = DESCRIBED_TYPE.matcher(descriptor);
    while (matcher.find()) {
      used.add(matcher.group(1));
    }
  }

  private static boolean isOnTheHelperClassPath(final String name) {
    return (
      name.startsWith("java/") ||
      name.startsWith("javax/") ||
      name.startsWith("jdk/") ||
      name.startsWith("org/cef/") ||
      name.startsWith("me/friwi/jcefmaven/")
    );
  }
}
