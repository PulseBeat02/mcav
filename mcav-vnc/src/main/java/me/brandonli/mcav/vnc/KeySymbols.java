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
package me.brandonli.mcav.vnc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.IOUtils;

/**
 * The X11 keysym table used to translate key names such as {@code Return} into the codes the VNC protocol
 * sends.
 */
final class KeySymbols {

  private static final String RESOURCE = "keysyms.json";
  private static final Map<String, Integer> SYMBOLS = load();

  private KeySymbols() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static Map<String, Integer> load() {
    final Reader reader = IOUtils.getResourceAsStreamReader(RESOURCE);
    return parse(reader);
  }

  /**
   * Reads a keysym table, a JSON object that maps key names to hexadecimal codes, and closes the reader.
   *
   * @param reader the reader of the table
   * @return the codes by key name
   * @throws PlayerException if the table is empty or cannot be read
   */
  @VisibleForTesting
  static Map<String, Integer> parse(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<Map<String, String>> token = new TypeToken<>() {};
    final Type type = token.getType();
    try (reader) {
      final Map<String, String> table = gson.fromJson(reader, type);
      if (table == null) {
        throw new PlayerException("The keysym resource " + RESOURCE + " is empty");
      }

      final Map<String, Integer> symbols = new HashMap<>();
      for (final Map.Entry<String, String> entry : table.entrySet()) {
        final String name = entry.getKey();
        final String hexadecimalCode = entry.getValue();
        final int code = Integer.decode(hexadecimalCode);
        symbols.put(name, code);
      }
      return Map.copyOf(symbols);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to read the keysym resource: " + message, exception);
    }
  }

  /**
   * Looks up the keysym of a key name.
   *
   * @param name the key name, such as {@code Return}
   * @return the keysym, or empty if the name is not a key
   */
  static OptionalInt lookup(final String name) {
    Preconditions.checkNotNull(name, "Name must not be null");
    final Integer code = SYMBOLS.get(name);
    if (code == null) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(code);
  }
}
