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
package me.brandonli.mcav.plugin.locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.utils.IoUtils;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Tag("fuzz")
final class TranslationManagerFuzzTest {

  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-plugin-messages-fuzz";
  private static final String MESSAGES_FILE = "locale/mcav_en_us.properties";

  private static final Set<String> BUNDLED_KEYS = readBundledKeys();

  @FuzzTest(maxDuration = "30s")
  void shouldLoadAnyMessagesFileOrFailWithAPluginException(final FuzzedDataProvider data) throws IOException {
    final byte[] contents = data.consumeRemainingAsBytes();
    final Path folder = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    final Path file = folder.resolve(MESSAGES_FILE);
    final Path localeFolder = file.getParent();
    try {
      Files.createDirectories(localeFolder);
      Files.write(file, contents);
      this.checkMessages(folder);
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(localeFolder);
      Files.delete(folder);
    }
  }

  private void checkMessages(final Path folder) {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File folderFile = folder.toFile();
    when(plugin.getDataFolder()).thenReturn(folderFile);
    final PluginDataConfigurationMapper mapper = mock(PluginDataConfigurationMapper.class);
    when(mapper.getLocale()).thenReturn(Locale.EN_US);
    when(plugin.getConfiguration()).thenReturn(mapper);
    try (final MockedStatic<JavaPlugin> javaPlugin = Mockito.mockStatic(JavaPlugin.class)) {
      javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      this.loadAndCheck();
    }
  }

  private void loadAndCheck() {
    final TranslationManager manager;
    try {
      manager = new TranslationManager();
    } catch (final PluginException exception) {
      return;
    }

    for (final String key : BUNDLED_KEYS) {
      final String message = "Bundled message %s must stay available!".formatted(key);
      assertDoesNotThrow(() -> manager.getProperty(key), message);
    }
  }

  private static Set<String> readBundledKeys() {
    try (
      final InputStream stream = IoUtils.getResourceAsStream(MESSAGES_FILE);
      final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
    ) {
      final ResourceBundle bundle = new PropertyResourceBundle(reader);
      final Set<String> keys = bundle.keySet();
      return Set.copyOf(keys);
    } catch (final IOException exception) {
      throw new AssertionError(exception);
    }
  }
}
