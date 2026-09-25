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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import org.cef.CefApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelperLauncherTest {

  private static final Path JAVA = Path.of("/opt/java/bin/java");
  private static final List<Path> CLASS_PATH = List.of(Path.of("/libs/mcav-browser.jar"), Path.of("/libs/jcef-api.jar"));

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
  void theEnvironmentKeepsLittleAndGetsThePrivateDisplay() throws IOException {
    final Map<String, String> server = Map.of("PATH", "/usr/bin", "DISCORD_TOKEN", "secret", "DISPLAY", ":0");
    final HelperLauncher launcher = launcher(OS.LINUX, server);
    assertEquals(Map.of("PATH", "/usr/bin"), launcher.createEnvironment(null));
    assertEquals(OS.LINUX, launcher.getOs());
    assertEquals(5_000L, launcher.getStartTimeoutMillis());
  }

  @Test
  void onlyLinuxNeedsXvfb() {
    assertEquals(Optional.empty(), launcher(OS.WINDOWS, Map.of()).findXvfb());
    assertEquals(Optional.empty(), launcher(OS.MAC, Map.of()).findXvfb());
  }

  @Test
  void linuxWithoutXvfbNamesThePackageToInstall() {
    final PlayerException failure = assertThrows(PlayerException.class, () ->
      launcher(OS.LINUX, Map.of("PATH", this.folder.toString())).findXvfb()
    );
    assertTrue(failure.getMessage().contains("install the package xvfb"), failure.getMessage());
    assertThrows(PlayerException.class, () -> launcher(OS.LINUX, Map.of()).findXvfb());
  }

  @Test
  void linuxFindsXvfbOnThePath() throws IOException {
    final Path program = Files.createFile(this.folder.resolve(XvfbDisplay.PROGRAM));
    if (this.folder.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
    }
    assertEquals(Optional.of(program), launcher(OS.LINUX, Map.of("PATH", this.folder.toString())).findXvfb());
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
}
