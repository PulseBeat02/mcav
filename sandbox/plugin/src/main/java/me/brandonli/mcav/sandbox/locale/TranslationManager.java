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

import static java.util.Objects.requireNonNull;
import static net.kyori.adventure.key.Key.key;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.locale.minimessage.PluginTranslator;
import me.brandonli.mcav.sandbox.utils.IOUtils;
import me.brandonli.mcav.sandbox.utils.Keys;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads the messages of the configured language and renders translatable components with them.
 *
 * <p>The messages are read from {@code locale/mcav_<language>.properties} in the plugin's data folder, so server
 * owners can edit them. The bundled file is copied there when it does not exist yet, and messages missing from the
 * file, such as messages added by a newer version of the plugin, are taken from the bundled file.
 */
public final class TranslationManager {

  private static final Locale DEFAULT_LOCALE = Locale.getDefault();
  private static final Key ADVENTURE_KEY = key(Keys.NAMESPACE, "main");

  private final ResourceBundle bundle;
  private final PluginTranslator translator;

  /**
   * Loads the messages of the language configured in the plugin.
   *
   * @throws IllegalStateException if the messages cannot be copied to or read from the data folder
   */
  public TranslationManager() {
    final MCAVSandbox plugin = JavaPlugin.getPlugin(MCAVSandbox.class);
    final PluginDataConfigurationMapper mapper = plugin.getConfiguration();
    final me.brandonli.mcav.sandbox.locale.Locale locale = mapper.getLocale();
    final Path folder = IOUtils.getPluginDataFolderPath();
    this.bundle = loadBundle(folder, locale);
    this.translator = new PluginTranslator(ADVENTURE_KEY, this.bundle);
  }

  private static ResourceBundle loadBundle(final Path folder, final me.brandonli.mcav.sandbox.locale.Locale locale) {
    final String name = locale.name();
    final String lowerCaseName = name.toLowerCase(Locale.ROOT);
    final String propertiesPath = "locale/mcav_%s.properties".formatted(lowerCaseName);
    final Path file = folder.resolve(propertiesPath);
    try {
      final boolean missing = Files.notExists(file);
      if (missing) {
        copyBundledFile(propertiesPath, file);
      }
      final ResourceBundle bundled = readBundledFile(propertiesPath);
      try (final Reader reader = Files.newBufferedReader(file)) {
        return LayeredBundle.of(reader, bundled);
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new IllegalStateException("Failed to load the messages from " + file + ": " + message, exception);
    }
  }

  private static void copyBundledFile(final String propertiesPath, final Path file) throws IOException {
    final Path nullableParent = file.getParent();
    final Path parent = requireNonNull(nullableParent);
    Files.createDirectories(parent);
    try (final InputStream stream = IOUtils.getResourceAsStream(propertiesPath)) {
      Files.copy(stream, file);
    }
  }

  private static ResourceBundle readBundledFile(final String propertiesPath) throws IOException {
    try (
      final InputStream stream = IOUtils.getResourceAsStream(propertiesPath);
      final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
    ) {
      return new PropertyResourceBundle(reader);
    }
  }

  /**
   * Gets a message as it is written in the file, without rendering it.
   *
   * @param key the key of the message
   * @return the message
   * @throws NullPointerException               if the key is {@code null}
   * @throws java.util.MissingResourceException if there is no message with the key
   */
  public String getProperty(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return this.bundle.getString(key);
  }

  /**
   * Renders a translatable component with the messages of the configured language.
   *
   * @param component the component, whose key names the message and whose arguments fill its placeholders
   * @return the rendered component
   * @throws NullPointerException               if the component is {@code null}
   * @throws java.util.MissingResourceException if there is no message with the key of the component
   */
  public Component render(final TranslatableComponent component) {
    Preconditions.checkNotNull(component, "Component must not be null");
    final Component rendered = this.translator.translate(component, DEFAULT_LOCALE);
    if (rendered == null) {
      final String key = component.key();
      throw new MissingResourceException("No configured translation for " + key, PluginTranslator.class.getName(), key);
    }
    return rendered;
  }

  /**
   * The messages of the data folder, with the bundled messages as the fallback for keys the file does not have.
   */
  private static final class LayeredBundle extends PropertyResourceBundle {

    private LayeredBundle(final Reader reader) throws IOException {
      super(reader);
    }

    static LayeredBundle of(final Reader reader, final ResourceBundle defaults) throws IOException {
      final LayeredBundle bundle = new LayeredBundle(reader);
      bundle.setParent(defaults);
      return bundle;
    }
  }
}
