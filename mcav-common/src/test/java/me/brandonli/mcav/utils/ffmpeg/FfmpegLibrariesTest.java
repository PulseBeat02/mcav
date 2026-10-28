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
package me.brandonli.mcav.utils.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstallers;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

final class FfmpegLibrariesTest {

  private static final Platform LINUX_X86_64 = Platform.ofPlatform(Os.LINUX, Arch.X86, Bits.BITS_64);

  private static final String PACKAGE_URL = "https://deb.example/pool/libasound2.deb";
  private static final String LOAD_ORDER_FILE = "load-order.txt";
  private static final String INSTALLATION_FOLDER = "debian-ffmpeg";

  private static final String LIBRARY_PATH_VARIABLE = "LD_LIBRARY_PATH";
  private static final String INHERITED_LIBRARY_PATH = "/opt/lib";

  private static final String EXECUTABLE_FOLDER = "natives";
  private static final Path EXECUTABLE = Path.of(EXECUTABLE_FOLDER, "ffmpeg");

  @Test
  void shouldInstallNothingWhereThePlatformHasNoPackages(@TempDir final Path folder) {
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(folder, new Download[0], LINUX_X86_64, this::refuseToLoad);
    final FfmpegLibraries libraries = FfmpegLibraries.install(Os.LINUX, this::refuseToLookUp, () -> installer);
    final Map<String, String> environment = libraries.createEnvironment(EXECUTABLE, INHERITED_LIBRARY_PATH);
    final String expectedLibraryPath = String.join(File.pathSeparator, EXECUTABLE_FOLDER, INHERITED_LIBRARY_PATH);
    final Map<String, String> expectedEnvironment = Map.of(LIBRARY_PATH_VARIABLE, expectedLibraryPath);
    assertEquals(expectedEnvironment, environment);
  }

  @Test
  void shouldUseTheLibrariesOfTheSystem(@TempDir final Path folder) {
    final Download[] downloads = { new Download(LINUX_X86_64, PACKAGE_URL) };
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(folder, downloads, LINUX_X86_64, this::refuseToLoad);
    final FfmpegLibraries libraries = FfmpegLibraries.install(Os.LINUX, library -> true, () -> installer);
    final Map<String, String> environment = libraries.createEnvironment(EXECUTABLE, INHERITED_LIBRARY_PATH);
    final String expectedLibraryPath = String.join(File.pathSeparator, EXECUTABLE_FOLDER, INHERITED_LIBRARY_PATH);
    final Map<String, String> expectedEnvironment = Map.of(LIBRARY_PATH_VARIABLE, expectedLibraryPath);
    assertEquals(expectedEnvironment, environment);
  }

  @Test
  void shouldInstallAndLoadTheLibrariesTheSystemLacks(@TempDir final Path folder) throws IOException {
    final String soundLibraryPath = "usr/lib/x86_64-linux-gnu/libasound.so.2.0.0";
    final String pulseLibraryPath = "usr/lib/x86_64-linux-gnu/pulseaudio/libpulsecommon-16.1.so";
    final Path soundLibrary = this.createLibrary(folder, soundLibraryPath);
    final Path pulseLibrary = this.createLibrary(folder, pulseLibraryPath);
    final List<String> loadOrder = List.of(soundLibraryPath, pulseLibraryPath);
    Files.write(folder.resolve(LOAD_ORDER_FILE), loadOrder, StandardCharsets.UTF_8);
    final Download[] downloads = { new Download(LINUX_X86_64, PACKAGE_URL) };
    final List<String> loaded = new ArrayList<>();
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(folder, downloads, LINUX_X86_64, loaded::add);
    final Predicate<String> systemLibraries = Predicate.not("libasound.so.2"::equals);

    final FfmpegLibraries libraries = FfmpegLibraries.install(Os.LINUX, systemLibraries, () -> installer);

    final Map<String, String> environment = libraries.createEnvironment(EXECUTABLE, null);
    final List<String> expectedLoaded = List.of(soundLibrary.toString(), pulseLibrary.toString());
    final Path soundFolder = soundLibrary.getParent();
    final Path pulseFolder = pulseLibrary.getParent();
    final String expectedLibraryPath = String.join(File.pathSeparator, EXECUTABLE_FOLDER, soundFolder.toString(), pulseFolder.toString());
    final Map<String, String> expectedEnvironment = Map.of(LIBRARY_PATH_VARIABLE, expectedLibraryPath);
    assertEquals(expectedLoaded, loaded);
    assertEquals(expectedEnvironment, environment);
  }

  private Path createLibrary(final Path folder, final String libraryPath) throws IOException {
    final Path library = folder.resolve(libraryPath);
    final Path libraryFolder = library.getParent();
    Files.createDirectories(libraryFolder);
    Files.writeString(library, libraryPath, StandardCharsets.UTF_8);
    return library;
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldWarnWithoutFailingWhenTheLibrariesCannotBeInstalled(@TempDir final Path folder) {
    final Path installation = folder.resolve(INSTALLATION_FOLDER);
    final Download[] downloads = { new Download(LINUX_X86_64, PACKAGE_URL) };
    final DebianLibraryInstaller installer = DebianLibraryInstallers.create(installation, downloads, LINUX_X86_64, this::refuseToLoad);
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    final FfmpegLibraries libraries;
    try {
      libraries = FfmpegLibraries.install(Os.LINUX, library -> false, () -> installer);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean warned = log.contains("The system libraries of FFmpeg could not be installed, so FFmpeg may fail to load.");
    final boolean namesTheDownload = log.contains("Debian package %s has no pinned hash!".formatted(PACKAGE_URL));
    final Map<String, String> environment = libraries.createEnvironment(EXECUTABLE, INHERITED_LIBRARY_PATH);
    final String expectedLibraryPath = String.join(File.pathSeparator, EXECUTABLE_FOLDER, INHERITED_LIBRARY_PATH);
    final Map<String, String> expectedEnvironment = Map.of(LIBRARY_PATH_VARIABLE, expectedLibraryPath);
    assertTrue(warned, log);
    assertTrue(namesTheDownload, log);
    assertEquals(expectedEnvironment, environment);
  }

  @Test
  void shouldPutTheFoldersBeforeTheInheritedLibraryPath() {
    final Path first = Path.of("first");
    final Path second = Path.of("second");
    final List<Path> folders = List.of(first, second);
    final FfmpegLibraries libraries = new FfmpegLibraries(Os.LINUX, folders);
    final Map<String, String> inherited = libraries.createEnvironment(EXECUTABLE, INHERITED_LIBRARY_PATH);
    final Map<String, String> empty = libraries.createEnvironment(EXECUTABLE, "");
    final Map<String, String> unset = libraries.createEnvironment(EXECUTABLE, null);
    final String folderPath = String.join(File.pathSeparator, EXECUTABLE_FOLDER, first.toString(), second.toString());
    final Map<String, String> expectedInherited = Map.of(LIBRARY_PATH_VARIABLE, folderPath + File.pathSeparator + INHERITED_LIBRARY_PATH);
    final Map<String, String> expectedAlone = Map.of(LIBRARY_PATH_VARIABLE, folderPath);
    assertEquals(expectedInherited, inherited);
    assertEquals(expectedAlone, empty);
    assertEquals(expectedAlone, unset);
  }

  @Test
  void shouldSetNoEnvironmentOutsideLinux() {
    final Path folder = Path.of("libraries");
    final List<Path> folders = List.of(folder);
    final FfmpegLibraries libraries = new FfmpegLibraries(Os.WINDOWS, folders);
    final Map<String, String> environment = libraries.createEnvironment(EXECUTABLE, INHERITED_LIBRARY_PATH);
    assertEquals(Map.of(), environment);
  }

  @Test
  void shouldReadTheInheritedLibraryPathOfTheJvm() {
    final Path folder = Path.of("libraries");
    final List<Path> folders = List.of(folder);
    final FfmpegLibraries libraries = new FfmpegLibraries(Os.LINUX, folders);
    final Map<String, String> environment = libraries.getEnvironment(EXECUTABLE);
    final String inherited = System.getenv(LIBRARY_PATH_VARIABLE);
    final Map<String, String> expected = libraries.createEnvironment(EXECUTABLE, inherited);
    assertEquals(expected, environment);
  }

  @Test
  void shouldInstallTheLibrariesOnce() throws ReflectiveOperationException {
    final FfmpegLibraries first = FfmpegLibraries.installOnce();
    final FfmpegLibraries second = FfmpegLibraries.installOnce();
    assertNotNull(first);
    assertSame(first, second);

    final Class<?>[] nestedClasses = FfmpegLibraries.class.getDeclaredClasses();
    assertEquals(1, nestedClasses.length);

    UtilityClassAssertions.assertNotInstantiable(nestedClasses[0]);
  }

  private boolean refuseToLookUp(final String library) {
    return fail("Libraries are only looked up where the platform has packages!");
  }

  private void refuseToLoad(final String library) {
    fail("Libraries are only loaded where the system lacks one!");
  }
}
