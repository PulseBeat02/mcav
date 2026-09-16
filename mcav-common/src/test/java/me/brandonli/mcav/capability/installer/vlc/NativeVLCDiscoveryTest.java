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
import me.brandonli.mcav.capability.installer.vlc.discovery.VLCDiscoveryStrategies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy;

/**
 * Tests {@link NativeVLCDiscovery} against the VLC installed on this machine. The tests are skipped where VLC is not
 * installed.
 *
 * <p>vlcj remembers a successful discovery in a static field for the rest of the JVM's lifetime. Each test clears
 * that memory first, so it sees a fresh discovery no matter which tests ran before. Loading libvlc a second time is
 * harmless, because the library stays loaded.
 */
final class NativeVLCDiscoveryTest {

  private boolean previouslyFound;

  @BeforeEach
  void rememberVlcjState() throws ReflectiveOperationException {
    final Field alreadyFound = alreadyFoundField();
    this.previouslyFound = alreadyFound.getBoolean(null);
  }

  @AfterEach
  void restoreVlcjState() throws ReflectiveOperationException {
    // other tests of this JVM see vlcj exactly as it was before this test
    final Field alreadyFound = alreadyFoundField();
    alreadyFound.setBoolean(null, this.previouslyFound);
  }

  @Test
  void loadsTheSystemInstallationAndThenRemembersIt(@TempDir final Path emptyDirectory) throws ReflectiveOperationException {
    final Path systemDirectory = requireSystemInstallation();
    forgetEarlierDiscoveries();
    final NativeVLCDiscovery discovery = new NativeVLCDiscovery();
    final boolean loadedFromEmptyDirectory = discovery.loadInstalledLibraries(emptyDirectory);
    final Path pathAfterFailure = discovery.getDiscoveredPath();
    assertFalse(loadedFromEmptyDirectory);
    assertNull(pathAfterFailure);
    final boolean found = discovery.discoverSystemInstallation();
    final Path discoveredPath = discovery.getDiscoveredPath();
    assertTrue(found);
    assertEquals(systemDirectory, discoveredPath);
    final boolean foundAgain = discovery.loadInstalledLibraries(emptyDirectory);
    final Path pathOfRememberedDiscovery = discovery.getDiscoveredPath();
    assertTrue(foundAgain, "vlcj remembers a successful discovery");
    assertNull(pathOfRememberedDiscovery);
  }

  @Test
  void loadsAnInstallationFromItsLibraryDirectory() throws ReflectiveOperationException {
    final Path systemDirectory = requireSystemInstallation();
    forgetEarlierDiscoveries();
    final NativeVLCDiscovery discovery = new NativeVLCDiscovery();
    final boolean loaded = discovery.loadInstalledLibraries(systemDirectory);
    final Path discoveredPath = discovery.getDiscoveredPath();
    assertTrue(loaded);
    assertEquals(systemDirectory, discoveredPath);
  }

  private static Path requireSystemInstallation() {
    final NativeDiscoveryStrategy[] strategies = VLCDiscoveryStrategies.createSystemStrategies();
    for (final NativeDiscoveryStrategy strategy : strategies) {
      final boolean supported = strategy.supported();
      final String directory = supported ? strategy.discover() : null;
      if (directory != null) {
        return Path.of(directory);
      }
    }
    Assumptions.abort("VLC is not installed on this machine");
    throw new AssertionError("unreachable");
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
