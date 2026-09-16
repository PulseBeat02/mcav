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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import me.brandonli.mcav.capability.installer.Installer;
import me.brandonli.mcav.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link VLCInstallationKit}.
 */
final class VLCInstallationKitTest {

  private static final Path SYSTEM_DIRECTORY = Path.of("system", "vlc");
  private static final Path INSTALLED_DIRECTORY = Path.of("cache", "vlc", "lib");

  @Test
  void prefersTheSystemInstallationAndNeverTouchesTheInstaller() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final Optional<Path> expected = Optional.of(SYSTEM_DIRECTORY);
    assertEquals(expected, directory);
    verify(installer, never()).isSupported();
    verify(installer, never()).download(anyBoolean());
    verify(discovery, never()).loadInstalledLibraries(any());
  }

  @Test
  void acceptsLibrariesVlcjLoadedBeforeWithoutALocation() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(null);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final boolean noLocation = directory.isEmpty();
    assertTrue(noLocation);
    verify(installer, never()).download(anyBoolean());
  }

  @Test
  void installsAndLoadsVlcWhenTheSystemHasNone() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    final Optional<Path> expected = Optional.of(INSTALLED_DIRECTORY);
    assertEquals(expected, directory);
    verify(installer).download(true);
    verify(discovery).loadInstalledLibraries(INSTALLED_DIRECTORY);
  }

  @Test
  void failsWithoutDownloadingWhenVlcCannotBeInstalled() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(false);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    verify(installer, never()).download(anyBoolean());
  }

  @Test
  void failsWhenTheInstalledLibrariesCannotBeLoaded() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(false);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final UnsupportedOperatingSystemException thrown = assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    assertNamesTheInstalledDirectory(thrown);
  }

  @Test
  void propagatesDownloadFailuresAndRetriesOnTheNextStart() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    final IOException failure = new IOException("offline");
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    when(installer.isSupported()).thenReturn(true);
    when(installer.download(true)).thenThrow(failure).thenReturn(INSTALLED_DIRECTORY);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final IOException thrown = assertThrows(IOException.class, kit::start);
    assertSame(failure, thrown);
    final Optional<Path> retried = kit.start();
    final Optional<Path> expected = Optional.of(INSTALLED_DIRECTORY);
    assertEquals(expected, retried);
  }

  @Test
  void loadsVlcOnlyOncePerLoadState() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(true);
    when(discovery.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);
    final VLCLoadState sharedState = new VLCLoadState();
    final VLCInstallationKit first = new VLCInstallationKit(installer, discovery, sharedState);
    final VLCInstallationKit second = new VLCInstallationKit(installer, discovery, sharedState);
    first.start();
    final Optional<Path> secondResult = second.start();
    final Optional<Path> expected = Optional.of(SYSTEM_DIRECTORY);
    assertEquals(expected, secondResult);
    verify(discovery, times(1)).discoverSystemInstallation();
  }

  @Test
  void prefersAnEarlierPrivateInstallationWithoutAskingTheNetwork() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    final Optional<Path> earlierInstallation = Optional.of(INSTALLED_DIRECTORY);
    when(installer.findInstallation()).thenReturn(earlierInstallation);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(true);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final Optional<Path> directory = kit.start();
    assertEquals(earlierInstallation, directory);
    verify(installer, never()).isSupported();
    verify(installer, never()).download(anyBoolean());
  }

  @Test
  void failsWhenAnEarlierPrivateInstallationCannotBeLoaded() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery discovery = mock(VLCDiscovery.class);
    when(discovery.discoverSystemInstallation()).thenReturn(false);
    final Optional<Path> earlierInstallation = Optional.of(INSTALLED_DIRECTORY);
    when(installer.findInstallation()).thenReturn(earlierInstallation);
    when(discovery.loadInstalledLibraries(INSTALLED_DIRECTORY)).thenReturn(false);
    final VLCInstallationKit kit = kitWithOwnState(installer, discovery);
    final UnsupportedOperatingSystemException thrown = assertThrows(UnsupportedOperatingSystemException.class, kit::start);
    assertNamesTheInstalledDirectory(thrown);
    verify(installer, never()).download(anyBoolean());
  }

  @Test
  void logsWhereTheLibrariesCameFrom() throws IOException {
    final Installer installer = mock(Installer.class);
    final VLCDiscovery withoutLocation = mock(VLCDiscovery.class);
    when(withoutLocation.discoverSystemInstallation()).thenReturn(true);
    when(withoutLocation.getDiscoveredPath()).thenReturn(null);
    final VLCDiscovery withLocation = mock(VLCDiscovery.class);
    when(withLocation.discoverSystemInstallation()).thenReturn(true);
    when(withLocation.getDiscoveredPath()).thenReturn(SYSTEM_DIRECTORY);

    final VLCInstallationKit earlierKit = kitWithOwnState(installer, withoutLocation);
    final VLCInstallationKit systemKit = kitWithOwnState(installer, withLocation);
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
  void createsKitsThatShareOneLoadState(@TempDir final Path folder, @TempDir final Path home) {
    final VLCInstaller installer = VLCInstaller.create(folder);
    final VLCInstallationKit custom = VLCInstallationKit.create(installer);
    final VLCInstallationKit standard;
    final Path expectedFolder;
    try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(home)) {
      standard = VLCInstallationKit.create();
      final Path redirectedHome = redirected.getHome();
      final Path mcavFolder = redirectedHome.resolve(".mcav");
      expectedFolder = mcavFolder.resolve("cache");
    }

    final Installer customInstaller = custom.getInstaller();
    final Installer standardInstaller = standard.getInstaller();
    final VLCInstaller standardVlcInstaller = assertInstanceOf(VLCInstaller.class, standardInstaller);
    final Path standardFolder = standardVlcInstaller.getFolder();
    final VLCLoadState customState = custom.getLoadState();
    final VLCLoadState standardState = standard.getLoadState();
    assertSame(installer, customInstaller);
    assertEquals(expectedFolder, standardFolder);
    assertSame(customState, standardState, "libvlc is loaded once per JVM, whichever kit loads it");
  }

  @Test
  void rejectsANullInstaller() {
    assertThrows(NullPointerException.class, () -> VLCInstallationKit.create(null));
  }

  private static VLCInstallationKit kitWithOwnState(final Installer installer, final VLCDiscovery discovery) {
    final VLCLoadState loadState = new VLCLoadState();
    return new VLCInstallationKit(installer, discovery, loadState);
  }

  private static void assertNamesTheInstalledDirectory(final UnsupportedOperatingSystemException thrown) {
    final String message = thrown.getMessage();
    final String directoryName = INSTALLED_DIRECTORY.toString();
    final boolean namesTheDirectory = message.contains(directoryName);
    assertTrue(namesTheDirectory, message);
  }

  private static String captureStandardError(final VLCInstallationKit kit) throws IOException {
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
