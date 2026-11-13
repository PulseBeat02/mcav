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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.MissingResourceException;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.utils.IoUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class TranslationManagerTest {

  private static final String RESOURCE = "locale/mcav_en_us.properties";

  private static final String HELP_COMMAND_KEY = "mcav.command.help.command";
  private static final String HELP_COMMAND_TITLE = "Command";

  private static final String EDITED_SCREEN_BUILD_LINE = "mcav.command.screen.build=<gold>Screen ready</gold>\n";

  private static final String UNKNOWN_KEY = "mcav.unknown";

  private static final String LOCALE_FOLDER_NAME = "locale";

  @TempDir
  private Path folder;

  private MockedStatic<JavaPlugin> javaPlugin;

  @BeforeEach
  void mockPlugin() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File folderFile = this.folder.toFile();
    when(plugin.getDataFolder()).thenReturn(folderFile);
    final PluginDataConfigurationMapper mapper = mock(PluginDataConfigurationMapper.class);
    when(mapper.getLocale()).thenReturn(Locale.EN_US);
    when(plugin.getConfiguration()).thenReturn(mapper);
    this.javaPlugin = Mockito.mockStatic(JavaPlugin.class);
    this.javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
  }

  @AfterEach
  void closeStaticMock() {
    this.javaPlugin.close();
  }

  @Test
  void shouldCopyTheBundledMessagesIntoTheDataFolder() throws IOException {
    final TranslationManager manager = new TranslationManager();
    final Path file = this.folder.resolve(RESOURCE);
    final byte[] copied = Files.readAllBytes(file);
    final byte[] bundled = this.readBundledMessages();
    assertArrayEquals(bundled, copied);

    final String title = manager.getProperty(HELP_COMMAND_KEY);
    assertEquals(HELP_COMMAND_TITLE, title);
  }

  private byte[] readBundledMessages() throws IOException {
    try (final InputStream stream = IoUtils.getResourceAsStream(RESOURCE)) {
      return stream.readAllBytes();
    }
  }

  @Test
  void shouldUseTheMessagesEditedByTheServerOwner() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    Files.writeString(file, EDITED_SCREEN_BUILD_LINE, StandardCharsets.UTF_8);
    final TranslationManager manager = new TranslationManager();
    final TranslatableComponent component = Component.translatable("mcav.command.screen.build");
    final Component rendered = manager.render(component);
    final String text = Components.plain(rendered);
    assertEquals("Screen ready", text);
  }

  @Test
  void shouldTakeMessagesMissingFromTheEditedFileFromTheBundledFile() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    Files.writeString(file, EDITED_SCREEN_BUILD_LINE, StandardCharsets.UTF_8);
    final TranslationManager manager = new TranslationManager();
    final String title = manager.getProperty(HELP_COMMAND_KEY);
    assertEquals(HELP_COMMAND_TITLE, title);

    final String edited = Files.readString(file, StandardCharsets.UTF_8);
    assertEquals(EDITED_SCREEN_BUILD_LINE, edited);
    assertThrows(MissingResourceException.class, () -> manager.getProperty(UNKNOWN_KEY));
  }

  @Test
  void shouldRenderArgumentsIntoMessages() {
    final TranslationManager manager = new TranslationManager();
    final Component url = Component.text("https://example.com/");
    final TranslatableComponent component = Component.translatable("mcav.command.audio.http", url);
    final Component rendered = manager.render(component);
    final String text = Components.plain(rendered);
    assertEquals("Click on the URL to listen onto the website! https://example.com/", text);
  }

  @Test
  void shouldFailForUnknownKeys() {
    final TranslationManager manager = new TranslationManager();
    final TranslatableComponent component = Component.translatable(UNKNOWN_KEY);
    assertThrows(MissingResourceException.class, () -> manager.render(component));
  }

  @Test
  void shouldFailWhenTheMessagesCannotBeRead() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    Files.createDirectories(file);
    final PluginException exception = assertThrows(PluginException.class, TranslationManager::new);

    final String message = exception.getMessage();
    final boolean mentionsFile = message.equals("Messages from %s could not be loaded!".formatted(file));
    assertTrue(mentionsFile, message);
  }

  @Test
  void shouldFailWhenTheMessagesHaveAMalformedEscape() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    Files.writeString(file, "mcav.command.screen.build=\\u00zz\n", StandardCharsets.UTF_8);
    final PluginException exception = assertThrows(PluginException.class, TranslationManager::new);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IllegalArgumentException.class, cause);
  }

  @Test
  void shouldFailWhenTheMessagesAreNotUtf8() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    final byte[] contents = "mcav.command.screen.build=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(file, contents);
    final PluginException exception = assertThrows(PluginException.class, TranslationManager::new);

    final Throwable cause = exception.getCause();
    assertInstanceOf(CharacterCodingException.class, cause);
  }

  @Test
  void shouldFailWhenTheMessagesCannotBeCopied() throws IOException {
    final Path blocker = this.folder.resolve(LOCALE_FOLDER_NAME);
    Files.writeString(blocker, "not a folder", StandardCharsets.UTF_8);
    final PluginException exception = assertThrows(PluginException.class, TranslationManager::new);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldRefuseNullArguments() {
    final TranslationManager manager = new TranslationManager();
    assertThrows(NullPointerException.class, () -> manager.getProperty(null));
    assertThrows(NullPointerException.class, () -> manager.render(null));
  }
}
