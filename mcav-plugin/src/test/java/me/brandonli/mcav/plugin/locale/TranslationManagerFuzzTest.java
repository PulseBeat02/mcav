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
import com.google.common.primitives.Bytes;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.utils.IoUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Tag;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Tag("fuzz")
final class TranslationManagerFuzzTest {

  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-plugin-messages-fuzz";
  private static final String MESSAGES_FILE = "locale/mcav_en_us.properties";

  private static final Set<String> BUNDLED_KEYS = readBundledKeys();

  private static final String EDITED_KEY = "mcav.command.screen.build";

  private static final Component FIRST_ARGUMENT = Component.text("first");
  private static final Component SECOND_ARGUMENT = Component.text("second");
  private static final Component THIRD_ARGUMENT = Component.text("third");

  private static final List<List<Component>> ARGUMENT_LISTS = List.of(
    List.of(),
    List.of(FIRST_ARGUMENT),
    List.of(FIRST_ARGUMENT, SECOND_ARGUMENT),
    List.of(FIRST_ARGUMENT, SECOND_ARGUMENT, THIRD_ARGUMENT)
  );

  @FuzzTest(maxDuration = "30s")
  void shouldLoadAnyMessagesFileOrFailWithAPluginException(final FuzzedDataProvider data) throws IOException {
    final byte[] contents = data.consumeRemainingAsBytes();
    this.writeAndCheck(contents, this::checkBundledMessages);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldRenderAnyEditedMessageOrThrowADocumentedException(final FuzzedDataProvider data) throws IOException {
    final byte[] text = data.consumeRemainingAsBytes();
    final String assignment = "%s=".formatted(EDITED_KEY);
    final byte[] prefix = assignment.getBytes(StandardCharsets.UTF_8);
    final byte[] contents = Bytes.concat(prefix, text);
    this.writeAndCheck(contents, this::checkEditedMessage);
  }

  private void writeAndCheck(final byte[] contents, final Consumer<TranslationManager> check) throws IOException {
    final Path folder = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    final Path file = folder.resolve(MESSAGES_FILE);
    final Path localeFolder = file.getParent();
    try {
      Files.createDirectories(localeFolder);
      Files.write(file, contents);
      this.checkMessages(folder, check);
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(localeFolder);
      Files.delete(folder);
    }
  }

  private void checkMessages(final Path folder, final Consumer<TranslationManager> check) {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final File folderFile = folder.toFile();
    when(plugin.getDataFolder()).thenReturn(folderFile);
    final PluginDataConfigurationMapper mapper = mock(PluginDataConfigurationMapper.class);
    when(mapper.getLocale()).thenReturn(Locale.EN_US);
    when(plugin.getConfiguration()).thenReturn(mapper);
    try (final MockedStatic<JavaPlugin> javaPlugin = Mockito.mockStatic(JavaPlugin.class)) {
      javaPlugin.when(() -> JavaPlugin.getPlugin(MCAVSandbox.class)).thenReturn(plugin);
      this.loadAndCheck(check);
    }
  }

  private void loadAndCheck(final Consumer<TranslationManager> check) {
    final TranslationManager manager;
    try {
      manager = new TranslationManager();
    } catch (final PluginException exception) {
      return;
    }

    check.accept(manager);
  }

  private void checkBundledMessages(final TranslationManager manager) {
    for (final String key : BUNDLED_KEYS) {
      final String message = "Bundled message %s must stay available!".formatted(key);
      assertDoesNotThrow(() -> manager.getProperty(key), message);
    }
  }

  private void checkEditedMessage(final TranslationManager manager) {
    for (final List<Component> arguments : ARGUMENT_LISTS) {
      final TranslatableComponent component = Component.translatable(EDITED_KEY, arguments);
      this.renderAndCheck(manager, component);
    }
  }

  private void renderAndCheck(final TranslationManager manager, final TranslatableComponent component) {
    try {
      final Component _ = manager.render(component);
    } catch (final ParsingException | NoSuchElementException | IndexOutOfBoundsException exception) {
      // wrong MiniMessage in the owner's text, as ArgumentTag documents
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
