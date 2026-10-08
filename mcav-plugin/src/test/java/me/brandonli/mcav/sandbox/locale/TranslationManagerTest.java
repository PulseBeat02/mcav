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
package me.brandonli.mcav.sandbox.locale;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.MissingResourceException;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link TranslationManager}.
 */
final class TranslationManagerTest {

  private static final String RESOURCE = "locale/mcav_en_us.properties";

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

  private static byte[] readBundledMessages() throws IOException {
    try (final InputStream stream = IOUtils.getResourceAsStream(RESOURCE)) {
      return stream.readAllBytes();
    }
  }

  @Test
  void copiesTheBundledMessagesIntoTheDataFolder() throws IOException {
    final TranslationManager manager = new TranslationManager();
    final Path file = this.folder.resolve(RESOURCE);
    final byte[] copied = Files.readAllBytes(file);
    final byte[] bundled = readBundledMessages();
    assertArrayEquals(bundled, copied);
    final String title = manager.getProperty("mcav.command.help.command");
    assertEquals("Command", title);
  }

  @Test
  void usesTheMessagesEditedByTheServerOwner() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    Files.writeString(file, "mcav.command.screen.build=<gold>Screen ready</gold>\n", StandardCharsets.UTF_8);
    final TranslationManager manager = new TranslationManager();
    final TranslatableComponent component = Component.translatable("mcav.command.screen.build");
    final Component rendered = manager.render(component);
    final String text = Components.plain(rendered);
    assertEquals("Screen ready", text);
  }

  @Test
  void takesMessagesMissingFromTheEditedFileFromTheBundledFile() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    final Path parent = file.getParent();
    Files.createDirectories(parent);
    Files.writeString(file, "mcav.command.screen.build=<gold>Screen ready</gold>\n", StandardCharsets.UTF_8);
    final TranslationManager manager = new TranslationManager();
    final String title = manager.getProperty("mcav.command.help.command");
    assertEquals("Command", title);
    final String edited = Files.readString(file, StandardCharsets.UTF_8);
    assertEquals("mcav.command.screen.build=<gold>Screen ready</gold>\n", edited);
    assertThrows(MissingResourceException.class, () -> manager.getProperty("mcav.unknown"));
  }

  @Test
  void rendersArgumentsIntoMessages() {
    final TranslationManager manager = new TranslationManager();
    final Component url = Component.text("https://example.com/");
    final TranslatableComponent component = Component.translatable("mcav.command.audio.http", url);
    final Component rendered = manager.render(component);
    final String text = Components.plain(rendered);
    assertEquals("Click on the URL to listen onto the website! https://example.com/", text);
  }

  @Test
  void failsForUnknownKeys() {
    final TranslationManager manager = new TranslationManager();
    final TranslatableComponent component = Component.translatable("mcav.unknown");
    assertThrows(MissingResourceException.class, () -> manager.render(component));
  }

  @Test
  void failsWhenTheMessagesCannotBeRead() throws IOException {
    final Path file = this.folder.resolve(RESOURCE);
    Files.createDirectories(file);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, TranslationManager::new);
    final String message = exception.getMessage();
    final boolean mentionsFile = message.startsWith("Failed to load the messages from " + file);
    assertTrue(mentionsFile, message);
  }

  @Test
  void failsWhenTheMessagesCannotBeCopied() throws IOException {
    final Path blocker = this.folder.resolve("locale");
    Files.writeString(blocker, "not a folder", StandardCharsets.UTF_8);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, TranslationManager::new);
    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void refusesNullArguments() {
    final TranslationManager manager = new TranslationManager();
    assertThrows(NullPointerException.class, () -> manager.getProperty(null));
    assertThrows(NullPointerException.class, () -> manager.render(null));
  }
}
