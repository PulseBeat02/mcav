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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Set;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.IOUtils;

/**
 * The key names {@link BrowserPlayer#sendKeyEvent(String)} presses rather than types: the W3C
 * {@code KeyboardEvent.key} values, such as {@code Enter}, {@code Backspace} or {@code ArrowLeft}, listed in the
 * {@code keybinds.json} resource.
 */
final class SpecialKeys {

  private static final String RESOURCE = "keybinds.json";
  private static final Set<String> SPECIAL_KEYS = loadSpecialKeys();

  private SpecialKeys() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static Set<String> loadSpecialKeys() {
    final Reader reader = IOUtils.getResourceAsStreamReader(RESOURCE);
    return parse(reader);
  }

  /**
   * Reads a key list, a JSON array of key names, and closes the reader.
   *
   * @param reader the reader of the list
   * @return the key names
   * @throws PlayerException if the list is empty or cannot be read
   */
  @VisibleForTesting
  static Set<String> parse(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<Set<String>> token = new TypeToken<>() {};
    final Type type = token.getType();
    try (reader) {
      final Set<String> keys = gson.fromJson(reader, type);
      if (keys == null) {
        throw new PlayerException("The key list resource " + RESOURCE + " is empty");
      }
      return Set.copyOf(keys);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to read the key list resource: " + message, exception);
    }
  }

  /**
   * Checks whether the text is the name of a special key rather than text to type.
   *
   * @param text the text passed to {@link BrowserPlayer#sendKeyEvent(String)}
   * @return true if the text names a key
   */
  static boolean isSpecialKey(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    return SPECIAL_KEYS.contains(text);
  }
}
