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
package me.brandonli.mcav.plugin.utils;

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
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class IoUtilsTest {

  private static final String DATA_FOLDER_PATH = "plugins/MCAV";
  private static final String CONFIGURATION_FILE_NAME = "config.yml";
  private static final String MISSING_RESOURCE_NAME = "missing.yml";

  @Test
  void shouldResolveTheDataFolderToAnAbsolutePath() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File relative = new File(DATA_FOLDER_PATH);
    when(plugin.getDataFolder()).thenReturn(relative);
    try (final MockedStatic<JavaPlugin> javaPlugin = Mockito.mockStatic(JavaPlugin.class)) {
      javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      final Path folder = IoUtils.getPluginDataFolderPath();
      final Path relativePath = relative.toPath();
      final Path expected = relativePath.toAbsolutePath();
      final boolean absolute = folder.isAbsolute();
      assertTrue(absolute);
      assertEquals(expected, folder);
    }
  }

  @Test
  void shouldOpenBundledResources() throws IOException {
    try (final InputStream stream = IoUtils.getResourceAsStream(CONFIGURATION_FILE_NAME)) {
      final byte[] bytes = stream.readAllBytes();
      final String content = new String(bytes, StandardCharsets.UTF_8);
      final boolean configuration = content.startsWith("# MCAV Configuration File");
      assertTrue(configuration);
    }
  }

  @Test
  void shouldFailForMissingResources() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> this.openAndClose(MISSING_RESOURCE_NAME));

    final String message = exception.getMessage();
    assertEquals("Resource missing.yml does not exist!", message);
  }

  private void openAndClose(final String path) throws IOException {
    try (final InputStream stream = IoUtils.getResourceAsStream(path)) {
      final String message = "Path %s opened as %s, but it must not open!".formatted(path, stream);
      fail(message);
    }
  }

  @Test
  void shouldRefuseANullPath() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> this.openAndClose(null));

    final String message = exception.getMessage();
    assertEquals("Path must not be null!", message);
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(IoUtils.class);
  }
}
