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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link IOUtils}.
 */
final class IOUtilsTest {

  @Test
  void resolvesTheDataFolderToAnAbsolutePath() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File relative = new File("plugins/MCAV");
    when(plugin.getDataFolder()).thenReturn(relative);
    try (final MockedStatic<JavaPlugin> javaPlugin = Mockito.mockStatic(JavaPlugin.class)) {
      javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      final Path folder = IOUtils.getPluginDataFolderPath();
      final Path relativePath = relative.toPath();
      final Path expected = relativePath.toAbsolutePath();
      final boolean absolute = folder.isAbsolute();
      assertTrue(absolute);
      assertEquals(expected, folder);
    }
  }

  @Test
  void opensBundledResources() throws IOException {
    try (final InputStream stream = IOUtils.getResourceAsStream("config.yml")) {
      final byte[] bytes = stream.readAllBytes();
      final String content = new String(bytes, StandardCharsets.UTF_8);
      final boolean configuration = content.startsWith("# MCAV Configuration File");
      assertTrue(configuration);
    }
  }

  private static void openAndClose(final String path) throws IOException {
    try (final InputStream stream = IOUtils.getResourceAsStream(path)) {
      fail("opened " + path + " as " + stream);
    }
  }

  @Test
  void failsForMissingResources() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> openAndClose("missing.yml"));
    final String message = exception.getMessage();
    assertEquals("missing.yml", message);
  }

  @Test
  void refusesANullPath() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> openAndClose(null));
    final String message = exception.getMessage();
    assertEquals("Path must not be null", message);
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(IOUtils.class);
  }
}
