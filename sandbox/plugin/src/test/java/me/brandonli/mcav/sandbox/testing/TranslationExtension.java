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
package me.brandonli.mcav.sandbox.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.locale.Locale;
import me.brandonli.mcav.sandbox.locale.LocaleTools;
import me.brandonli.mcav.sandbox.locale.TranslationManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Loads the messages of the plugin before any test runs.
 *
 * <p>{@link LocaleTools#MANAGER} is created once per JVM and asks Paper for the plugin, which only works inside a
 * server. This extension is registered for every test class through {@code junit-platform.properties} and creates
 * the manager with a mocked plugin whose data folder is a temporary directory.
 */
public final class TranslationExtension implements BeforeAllCallback {

  private static boolean initialized;

  /**
   * Constructs the extension. JUnit creates it for you.
   */
  public TranslationExtension() {
    // stateless
  }

  /**
   * Loads the messages before the first test class runs; later calls do nothing.
   *
   * @param context the context of the test class, which is not used
   */
  @Override
  public void beforeAll(final @NonNull ExtensionContext context) {
    initialize();
  }

  /**
   * Creates {@link LocaleTools#MANAGER} if it does not exist yet.
   */
  public static synchronized void initialize() {
    if (initialized) {
      return;
    }
    final Path folder = createTemporaryFolder();
    final File folderFile = folder.toFile();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    when(plugin.getDataFolder()).thenReturn(folderFile);
    final PluginDataConfigurationMapper mapper = mock(PluginDataConfigurationMapper.class);
    when(mapper.getLocale()).thenReturn(Locale.EN_US);
    when(plugin.getConfiguration()).thenReturn(mapper);
    try (final MockedStatic<JavaPlugin> mocked = Mockito.mockStatic(JavaPlugin.class)) {
      mocked.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      final TranslationManager manager = LocaleTools.MANAGER;
      final String title = manager.getProperty("mcav.command.help.command");
      if (title.isEmpty()) {
        throw new IllegalStateException("The messages were not loaded");
      }
    }
    deleteOnExit(folder);
    initialized = true;
  }

  private static Path createTemporaryFolder() {
    try {
      return Files.createTempDirectory("mcav-sandbox-messages");
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  // files registered later are deleted first, so the folder is registered before what it contains
  private static void deleteOnExit(final Path folder) {
    final Path localeFolder = folder.resolve("locale");
    final Path messages = localeFolder.resolve("mcav_en_us.properties");
    final File folderFile = folder.toFile();
    final File localeFile = localeFolder.toFile();
    final File messagesFile = messages.toFile();
    folderFile.deleteOnExit();
    localeFile.deleteOnExit();
    messagesFile.deleteOnExit();
  }
}
