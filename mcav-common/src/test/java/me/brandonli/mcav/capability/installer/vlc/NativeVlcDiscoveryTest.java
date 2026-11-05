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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import me.brandonli.mcav.capability.installer.vlc.discovery.VlcDiscoveryStrategies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

// vlcj keeps a successful discovery in a static field, which each test clears first; a second libvlc load is harmless, as it stays loaded.
final class NativeVlcDiscoveryTest {

  private boolean previouslyFound;

  @BeforeEach
  void rememberVlcjState() throws ReflectiveOperationException {
    final Field alreadyFound = alreadyFoundField();
    this.previouslyFound = alreadyFound.getBoolean(null);
  }

  @AfterEach
  void restoreVlcjState() throws ReflectiveOperationException {
    final Field alreadyFound = alreadyFoundField();
    alreadyFound.setBoolean(null, this.previouslyFound);
  }

  @Test
  void shouldLoadTheSystemInstallationAndThenRememberIt(@TempDir final Path emptyDirectory) throws ReflectiveOperationException {
    final Path installedDirectory = DownloadedVlc.install();
    forgetEarlierDiscoveries();
    final NativeVlcDiscovery discovery = new NativeVlcDiscovery(() -> VlcDiscoveryStrategies.createDirectoryStrategies(installedDirectory));
    final boolean loadedFromEmptyDirectory = discovery.loadInstalledLibraries(emptyDirectory);
    final Path pathAfterFailure = discovery.getDiscoveredPath();
    assertFalse(loadedFromEmptyDirectory);
    assertNull(pathAfterFailure);

    final boolean found = discovery.discoverSystemInstallation();
    final Path discoveredPath = discovery.getDiscoveredPath();
    assertTrue(found);
    assertEquals(installedDirectory, discoveredPath);

    final boolean foundAgain = discovery.loadInstalledLibraries(emptyDirectory);
    final Path pathOfRememberedDiscovery = discovery.getDiscoveredPath();
    assertTrue(foundAgain, "vlcj remembers a successful discovery");
    assertNull(pathOfRememberedDiscovery);
  }

  @Test
  void shouldFindNoSystemInstallationWhereTheSystemHasNone() throws ReflectiveOperationException {
    forgetEarlierDiscoveries();
    final NativeVlcDiscovery discovery = DownloadedVlc.createDiscovery();
    final boolean found = discovery.discoverSystemInstallation();
    final Path discoveredPath = discovery.getDiscoveredPath();
    assertFalse(found);
    assertNull(discoveredPath);
  }

  @Test
  void shouldLoadAnInstallationFromItsLibraryDirectory() throws ReflectiveOperationException {
    final Path installedDirectory = DownloadedVlc.install();
    forgetEarlierDiscoveries();
    final NativeVlcDiscovery discovery = new NativeVlcDiscovery();
    final boolean loaded = discovery.loadInstalledLibraries(installedDirectory);
    final Path discoveredPath = discovery.getDiscoveredPath();
    assertTrue(loaded);
    assertEquals(installedDirectory, discoveredPath);
  }

  private static Field alreadyFoundField() throws ReflectiveOperationException {
    final Field alreadyFound = NativeDiscovery.class.getDeclaredField("alreadyFound");
    alreadyFound.setAccessible(true);
    return alreadyFound;
  }

  private static void forgetEarlierDiscoveries() throws ReflectiveOperationException {
    final Field alreadyFound = alreadyFoundField();
    alreadyFound.setBoolean(null, false);
  }
}
