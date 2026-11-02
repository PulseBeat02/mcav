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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

// The X11 keysym table used to translate key names such as Return into the codes the VNC protocol sends.
final class KeySymbols {

  private static final String RESOURCE = "mcav/vnc/keysyms.json";
  private static final Map<String, Integer> SYMBOLS = load();

  private KeySymbols() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  private static Map<String, Integer> load() {
    final Reader reader = IoUtils.getResourceAsStreamReader(RESOURCE);
    return parse(reader);
  }

  // Reads a keysym table, a JSON object that maps key names to hexadecimal codes, and closes the reader.
  @VisibleForTesting
  static Map<String, Integer> parse(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    try (reader) {
      // a JsonObject rather than a Map, because Gson's map reader also takes arrays of pairs and fails on null keys
      final @Nullable JsonObject table = gson.fromJson(reader, JsonObject.class);
      if (table == null) {
        final String message = "Keysym resource %s is empty!".formatted(RESOURCE);
        throw new VncException(message);
      }

      return toCodes(table);
    } catch (final IOException | JsonParseException exception) {
      final String reason = exception.getMessage();
      final String message = "Because of \"%s\", keysym resource %s could not be read!".formatted(reason, RESOURCE);
      throw new VncException(message, exception);
    }
  }

  private static Map<String, Integer> toCodes(final JsonObject table) {
    final Map<String, Integer> symbols = new HashMap<>();
    for (final Map.Entry<String, JsonElement> entry : table.entrySet()) {
      final String name = entry.getKey();
      final JsonElement value = entry.getValue();
      final int code = decode(name, value);
      symbols.put(name, code);
    }

    return Map.copyOf(symbols);
  }

  private static int decode(final String name, final JsonElement value) {
    if (!value.isJsonPrimitive()) {
      final String message = "Keysym %s in resource %s has no code!".formatted(name, RESOURCE);
      throw new VncException(message);
    }

    final String hexadecimalCode = value.getAsString();
    try {
      return Integer.decode(hexadecimalCode);
    } catch (final NumberFormatException exception) {
      final String message = "Keysym %s in resource %s has the code \"%s\", which is not a number!".formatted(
        name,
        RESOURCE,
        hexadecimalCode
      );
      throw new VncException(message, exception);
    }
  }

  static OptionalInt lookup(final String name) {
    Preconditions.checkNotNull(name, "Name must not be null!");

    final @Nullable Integer code = SYMBOLS.get(name);
    if (code == null) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(code);
  }
}
