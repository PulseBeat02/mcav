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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.capability.installer.debian.DebianLibraryInstaller;
import me.brandonli.mcav.testing.TemporaryUserHome;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class VlcInstallationKitTest {

  private static final Path SYSTEM_DIRECTORY = Path.of("system", "vlc");
  private static final Path INSTALLED_DIRECTORY = Path.of("cache", "vlc", "lib");

  private static final String MCAV_DIRECTORY_NAME = ".mcav";
  private static final String CACHE_DIRECTORY_NAME = "cache";
  private static final String DEBIAN_VLC_NAME = "debian-vlc";

  @Test
  void shouldPreferTheSystemInstallationAndNeverTouchTheInstaller() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final Optional<Path> expected = Optional.of(SYSTEM_DIRECTORY);
    assertEquals(expected, directory);
    verify(installer, never()).isSupported();
    verify(installer, never()).download(any(FileMode.class));
    verify(discovery, never()).loadInstalledLibraries(any());
  }

  @Test
  void shouldAcceptLibrariesVlcjLoadedBeforeWithoutALocation() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(null);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final boolean noLocation = directory.isEmpty();
    assertTrue(noLocation);
    verify(installer, never()).download(any(FileMode.class));
  }

  @Test
  void shouldInstallAndLoadVlcWhenTheSystemHasNone() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final Optional<Path> expected = Optional.of(INSTALLED_DIRECTORY);
    assertEquals(expected, directory);
    verify(installer).download(FileMode.EXECUTABLE);
    verify(discovery).loadInstalledLibraries(INSTALLED_DIRECTORY);
  }

  @Test
  void shouldFailWithoutDownloadingWhenVlcCannotBeInstalled() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(false);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final UnsupportedOperatingSystemException thrown = assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    verify(installer, never()).download(any(FileMode.class));

    final String message = thrown.getMessage();
    final Platform platform = Platform.getCurrentPlatform();
    final String platformName = platform.toString();
    final boolean namesThePlatform = message.contains(platformName);
    assertTrue(namesThePlatform, message);
  }

  @Test
  void shouldFailWhenTheInstalledLibrariesCannotBeLoaded() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(false);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final UnsupportedOperatingSystemException thrown = assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    assertNamesTheInstalledDirectory(thrown);
  }

  @Test
  void shouldPropagateDownloadFailuresAndRetryOnTheNextStart() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    final InstallationException failure = new InstallationException("offline!");
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(FileMode.EXECUTABLE)).thenThrow(failure).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final InstallationException thrown = assertThrows(InstallationException.class, kit::start);
    assertSame(failure, thrown);

    final Optional<Path> retried = kit.start();
    final Optional<Path> expected = Optional.of(INSTALLED_DIRECTORY);
    assertEquals(expected, retried);
  }

  @Test
  void shouldLoadVlcOnlyOncePerLoadState() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);
    final VlcLoadState sharedState = new VlcLoadState();
    final VlcInstallationKit first = new VlcInstallationKit(installer, discovery, sharedState);
    final VlcInstallationKit second = new VlcInstallationKit(installer, discovery, sharedState);
    first.start();
    final Optional<Path> secondResult = second.start();
    final Optional<Path> expected = Optional.of(SYSTEM_DIRECTORY);
    assertEquals(expected, secondResult);
    verify(discovery, times(1)).discoverSystemInstallation();
  }

  @Test
  void shouldPreferAnEarlierPrivateInstallationWithoutAskingTheNetwork() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    final Optional<Path> earlierInstallation = Optional.of(INSTALLED_DIRECTORY);
    when(installer.findInstallation()).thenReturn(earlierInstallation);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    assertEquals(earlierInstallation, directory);
    verify(installer, never()).isSupported();
    verify(installer, never()).download(any(FileMode.class));
  }

  @Test
  void shouldFailWhenAnEarlierPrivateInstallationCannotBeLoaded() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery discovery = mock(VlcDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    final Optional<Path> earlierInstallation = Optional.of(INSTALLED_DIRECTORY);
    when(installer.findInstallation()).thenReturn(earlierInstallation);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(false);
    final VlcInstallationKit kit = kitWithOwnState(installer, discovery);
    final UnsupportedOperatingSystemException thrown = assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    assertNamesTheInstalledDirectory(thrown);
    verify(installer, never()).download(any(FileMode.class));
  }

  @Test
  void shouldLogWhereTheLibrariesCameFrom() throws IOException {
    final Installer installer = mock(Installer.class);
    final VlcDiscovery withoutLocation = mock(VlcDiscovery.class);
    when(withoutLocation.discoverSystemInstallation()).thenReturn(true);
    when(withoutLocation.getDiscoveredPath()).thenReturn(null);
    final VlcDiscovery withLocation = mock(VlcDiscovery.class);
    when(withLocation.discoverSystemInstallation()).thenReturn(true);
    when(withLocation.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);

    final VlcInstallationKit earlierKit = kitWithOwnState(installer, withoutLocation);
    final VlcInstallationKit systemKit = kitWithOwnState(installer, withLocation);
    final String earlierLog = captureStandardError(earlierKit);
    final String systemLog = captureStandardError(systemKit);
    final String systemDirectory = SYSTEM_DIRECTORY.toString();
    final boolean mentionsEarlierLoad = earlierLog.contains("vlcj loaded earlier");
    final boolean mentionsNull = earlierLog.contains("null");
    final boolean mentionsSystemDirectory = systemLog.contains(systemDirectory);
    assertTrue(mentionsEarlierLoad, earlierLog);
    assertFalse(mentionsNull, earlierLog);
    assertTrue(mentionsSystemDirectory, systemLog);
  }

  @Test
  void shouldCreateKitsThatShareOneLoadState(@TempDir final Path folder, @TempDir final Path home) {
    final VlcInstaller installer = VlcInstaller.create(folder);
    final VlcInstallationKit custom = VlcInstallationKit.create(installer);
    final VlcInstallationKit standard;
    try (final TemporaryUserHome _ = TemporaryUserHome.redirectTo(home)) {
      standard = VlcInstallationKit.create();
    }

    final Installer customInstaller = custom.getInstaller();
    final VlcLoadState customState = custom.getLoadState();
    final VlcLoadState standardState = standard.getLoadState();
    assertSame(installer, customInstaller);
    assertNotNull(customState, "the shared load state exists, rather than both kits returning null");
    assertSame(customState, standardState, "libvlc is loaded once per JVM, whichever kit loads it");
  }

  @ParameterizedTest
  @EnumSource(value = Arch.class, names = { "X86", "ARM" })
  void shouldInstallTheDebianPackagesOf64BitLinux(final Arch architecture, @TempDir final Path home) {
    final Platform linux = Platform.ofPlatform(Os.LINUX, architecture, Bits.BITS_64);
    final VlcInstallationKit kit;
    final Path expectedFolder;
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      kit = VlcInstallationKit.createForPlatform(linux);
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(MCAV_DIRECTORY_NAME);
      final Path cacheFolder = mcavFolder.resolve(CACHE_DIRECTORY_NAME);
      expectedFolder = cacheFolder.resolve(DEBIAN_VLC_NAME);
    }

    final Installer installer = kit.getInstaller();
    final DebianLibraryInstaller debianInstaller = assertInstanceOf(DebianLibraryInstaller.class, installer);
    final Path libraryDirectory = debianInstaller.getPath();
    final boolean insideCache = libraryDirectory.startsWith(expectedFolder);
    final VlcLoadState loadState = kit.getLoadState();
    final VlcInstaller customInstaller = VlcInstaller.create(home);
    final VlcInstallationKit custom = VlcInstallationKit.create(customInstaller);
    final VlcLoadState customState = custom.getLoadState();
    assertTrue(insideCache, libraryDirectory.toString());
    assertSame(customState, loadState);
  }

  @ParameterizedTest
  @EnumSource(value = Os.class, names = { "WINDOWS", "MAC", "FREEBSD" })
  void shouldDownloadVlcOnEveryOtherPlatform(final Os os, @TempDir final Path home) {
    final Platform platform = Platform.ofPlatform(os, Arch.X86, Bits.BITS_64);
    final VlcInstallationKit kit;
    final Path expectedFolder;
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      kit = VlcInstallationKit.createForPlatform(platform);
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(MCAV_DIRECTORY_NAME);
      expectedFolder = mcavFolder.resolve(CACHE_DIRECTORY_NAME);
    }

    final Installer installer = kit.getInstaller();
    final VlcInstaller vlcInstaller = assertInstanceOf(VlcInstaller.class, installer);
    final Path folder = vlcInstaller.getFolder();
    assertEquals(expectedFolder, folder);
  }

  @Test
  void shouldRejectANullInstaller() {
    assertThrows(NullPointerException.class, () -> VlcInstallationKit.create(null));
  }

  private static VlcInstallationKit kitWithOwnState(final Installer installer, final VlcDiscovery discovery) {
    final VlcLoadState loadState = new VlcLoadState();
    return new VlcInstallationKit(installer, discovery, loadState);
  }

  private static void assertNamesTheInstalledDirectory(final UnsupportedOperatingSystemException thrown) {
    final String message = thrown.getMessage();
    final String directoryName = INSTALLED_DIRECTORY.toString();
    final boolean namesTheDirectory = message.contains(directoryName);
    assertTrue(namesTheDirectory, message);
  }

  private static String captureStandardError(final VlcInstallationKit kit) throws IOException {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
    System.setErr(capture);
    try {
      kit.start();
    } finally {
      System.setErr(original);
    }

    return captured.toString(StandardCharsets.UTF_8);
  }
}
