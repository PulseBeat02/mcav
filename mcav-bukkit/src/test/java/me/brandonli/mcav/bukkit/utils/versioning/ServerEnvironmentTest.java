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
package me.brandonli.mcav.bukkit.utils.versioning;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests {@link ServerEnvironment} and {@link UnsupportedServerVersionException}.
 */
final class ServerEnvironmentTest {

  @Test
  void supportsTheExactVersionAndItsPatchReleasesOnly() {
    final boolean exact = ServerEnvironment.isSupported("26.2");
    final boolean patch = ServerEnvironment.isSupported("26.2.1");
    final boolean longerMinor = ServerEnvironment.isSupported("26.20");
    final boolean older = ServerEnvironment.isSupported("26.1");
    final boolean newerMajor = ServerEnvironment.isSupported("27.2");
    final boolean empty = ServerEnvironment.isSupported("");
    assertEquals("26.2", ServerEnvironment.SUPPORTED_MINECRAFT_VERSION);
    assertTrue(exact);
    assertTrue(patch);
    assertFalse(longerMinor);
    assertFalse(older);
    assertFalse(newerMajor);
    assertFalse(empty);
    assertThrows(NullPointerException.class, () -> ServerEnvironment.isSupported(null));
  }

  @Test
  void acceptsASupportedServer() {
    try (final FakeServer server = FakeServer.start()) {
      final MockedStatic<Bukkit> bukkit = server.getBukkit();
      bukkit.when(Bukkit::getMinecraftVersion).thenReturn("26.2.3");
      final String version = ServerEnvironment.getMinecraftVersion();
      final boolean supported = ServerEnvironment.isSupported();
      assertEquals("26.2.3", version);
      assertTrue(supported);
      assertDoesNotThrow(ServerEnvironment::checkSupported);
    }
  }

  @Test
  void rejectsAnUnsupportedServerWithAClearMessage() {
    try (final FakeServer server = FakeServer.start()) {
      final MockedStatic<Bukkit> bukkit = server.getBukkit();
      bukkit.when(Bukkit::getMinecraftVersion).thenReturn("1.21.4");
      final boolean supported = ServerEnvironment.isSupported();
      final UnsupportedServerVersionException exception = assertThrows(
        UnsupportedServerVersionException.class,
        ServerEnvironment::checkSupported
      );
      final String message = exception.getMessage();
      assertFalse(supported);
      assertEquals("MCAV only supports Minecraft 26.2, but the server is running 1.21.4!", message);
      assertInstanceOf(IllegalStateException.class, exception, "an unchecked exception that catch (Exception) handles");
    }
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(ServerEnvironment.class);
  }
}
