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
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

// listed in the mcav/browser/keybinds.json resource
final class SpecialKeys {

  private static final String RESOURCE = "mcav/browser/keybinds.json";
  private static final Set<String> SPECIAL_KEYS = loadSpecialKeys();

  private SpecialKeys() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  private static Set<String> loadSpecialKeys() {
    final Reader reader = IoUtils.getResourceAsStreamReader(RESOURCE);
    return parse(reader);
  }

  @VisibleForTesting
  static Set<String> parse(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<Set<String>> token = new TypeToken<>() {};
    final Type type = token.getType();
    try (reader) {
      final Set<String> keys = gson.fromJson(reader, type);
      if (keys == null || keys.isEmpty()) {
        final String message = "The key list resource %s is empty!".formatted(RESOURCE);
        throw new BrowserException(message);
      }

      return Set.copyOf(keys);
    } catch (final IOException exception) {
      final @Nullable String reason = exception.getMessage();
      final String message = "The key list resource could not be read (%s)!".formatted(reason);
      throw new BrowserException(message, exception);
    }
  }

  static boolean isSpecialKey(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null!");

    return SPECIAL_KEYS.contains(text);
  }
}
