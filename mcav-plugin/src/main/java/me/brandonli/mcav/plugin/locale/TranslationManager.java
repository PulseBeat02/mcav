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
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.locale.minimessage.PluginTranslator;
import me.brandonli.mcav.plugin.utils.IoUtils;
import me.brandonli.mcav.plugin.utils.Keys;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Loads the messages of the configured language and renders translatable components with them.
 *
 * <p>The messages are read from {@code locale/mcav_<language>.properties} in the plugin's data folder, so server
 * owners can edit them. The bundled file is copied there when it does not exist yet, and messages missing from the
 * file, such as messages added by a newer version of the plugin, are taken from the bundled file.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class TranslationManager {

  private static final java.util.Locale DEFAULT_LOCALE = java.util.Locale.getDefault(); // fqn: Locale is this package's language

  private static final Key ADVENTURE_KEY = key(Keys.NAMESPACE, "main");

  private static final String LOCALE_FILE_PREFIX = "locale/mcav_";
  private static final String LOCALE_FILE_SUFFIX = ".properties";

  private final ResourceBundle bundle;
  private final PluginTranslator translator;

  /**
   * Creates a manager that loads the messages of the language configured in the plugin.
   *
   * @throws PluginException if the messages cannot be copied to the data folder, or cannot be read from it as a UTF-8
   *                         properties file
   * @since 1.0.0
   */
  public TranslationManager() {
    final MCAVSandbox plugin = JavaPlugin.getPlugin(MCAVSandbox.class);
    final PluginDataConfigurationMapper mapper = plugin.getConfiguration();
    final Locale locale = mapper.getLocale();
    final Path folder = IoUtils.getPluginDataFolderPath();
    this.bundle = this.loadBundle(folder, locale);
    this.translator = new PluginTranslator(ADVENTURE_KEY, this.bundle);
  }

  private ResourceBundle loadBundle(@UnderInitialization TranslationManager this, final Path folder, final Locale locale) {
    final String name = locale.name();
    final String lowerCaseName = name.toLowerCase(java.util.Locale.ROOT); // fqn: Locale is this package's language
    final String propertiesPath = LOCALE_FILE_PREFIX + lowerCaseName + LOCALE_FILE_SUFFIX;
    final Path file = folder.resolve(propertiesPath);
    try {
      final boolean missing = Files.notExists(file);
      if (missing) {
        this.copyBundledFile(propertiesPath, file);
      }

      final ResourceBundle bundled = this.readBundledFile(propertiesPath);
      try (final Reader reader = Files.newBufferedReader(file)) {
        // Properties throws IllegalArgumentException for a malformed Unicode escape
        return LayeredBundle.of(reader, bundled);
      }
    } catch (final IOException | IllegalArgumentException exception) {
      final String message = "Messages from %s could not be loaded!".formatted(file);
      throw new PluginException(message, exception);
    }
  }

  private void copyBundledFile(@UnderInitialization TranslationManager this, final String propertiesPath, final Path file)
    throws IOException {
    final Path nullableParent = file.getParent();
    final Path parent = requireNonNull(nullableParent);
    Files.createDirectories(parent);
    try (final InputStream stream = IoUtils.getResourceAsStream(propertiesPath)) {
      Files.copy(stream, file);
    }
  }

  private ResourceBundle readBundledFile(@UnderInitialization TranslationManager this, final String propertiesPath) throws IOException {
    try (
      final InputStream stream = IoUtils.getResourceAsStream(propertiesPath);
      final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
    ) {
      return new PropertyResourceBundle(reader);
    }
  }

  /**
   * Returns a message as it is written in the file, without rendering it.
   *
   * @param key the key of the message
   * @return the message
   * @throws NullPointerException     if the key is {@code null}
   * @throws MissingResourceException if there is no message with the key
   * @since 1.0.0
   */
  public String getProperty(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null!");

    return this.bundle.getString(key);
  }

  /**
   * Renders a translatable component with the messages of the configured language.
   *
   * @param component the component, whose key names the message and whose arguments fill its placeholders
   * @return the rendered component
   * @throws NullPointerException     if the component is {@code null}
   * @throws MissingResourceException if there is no message with the key of the component
   * @since 1.0.0
   */
  public Component render(final TranslatableComponent component) {
    Preconditions.checkNotNull(component, "Component must not be null!");

    final Component rendered = this.translator.translate(component, DEFAULT_LOCALE);
    if (rendered == null) {
      final String key = component.key();
      final String message = "Translation key %s has no configured translation!".formatted(key);
      throw new MissingResourceException(message, PluginTranslator.class.getName(), key);
    }

    return rendered;
  }

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
