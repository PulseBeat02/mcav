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
package me.brandonli.mcav.sandbox.locale.minimessage;

import com.google.common.base.Preconditions;
import java.util.Locale;
import java.util.ResourceBundle;
import net.kyori.adventure.key.Key;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The translator of the plugin: renders MiniMessage messages read from the message file of the configured
 * language. Every message is taken from the same bundle, whatever locale Adventure asks for, so all players see
 * the language chosen in {@code config.yml}.
 */
public final class PluginTranslator extends MiniMessageTranslator {

  private final Key key;
  private final ResourceBundle bundle;

  /**
   * Creates the translator.
   *
   * @param key    the name of the translator, which identifies it to Adventure
   * @param bundle the messages, keyed like {@code mcav.command.image.load}
   * @throws NullPointerException if the key or the bundle is {@code null}
   */
  public PluginTranslator(final Key key, final ResourceBundle bundle) {
    Preconditions.checkNotNull(key, "Key must not be null");
    Preconditions.checkNotNull(bundle, "Bundle must not be null");
    this.key = key;
    this.bundle = bundle;
  }

  /**
   * Gets the MiniMessage text of a message from the bundle, whatever the locale.
   *
   * @param key    the key of the message, such as {@code mcav.command.image.load}
   * @param locale the locale Adventure renders for, which is ignored because the plugin serves one language
   * @return the message text
   * @throws java.util.MissingResourceException if the bundle has no message with the key
   */
  @Override
  protected String getMiniMessageString(final String key, final Locale locale) {
    return this.bundle.getString(key);
  }

  /**
   * Gets the name that identifies this translator to Adventure.
   *
   * @return the key given to the constructor
   */
  @Override
  public @NonNull Key name() {
    return this.key;
  }
}
