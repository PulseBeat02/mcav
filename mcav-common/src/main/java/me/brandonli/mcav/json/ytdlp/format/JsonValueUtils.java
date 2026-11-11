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
package me.brandonli.mcav.json.ytdlp.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

final class JsonValueUtils {

  private JsonValueUtils() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static @Nullable Object toUnmodifiable(final @Nullable Object value) {
    if (value instanceof final List<?> list) {
      return toUnmodifiableList(list);
    }

    if (value instanceof final Map<?, ?> map) {
      return toUnmodifiableMap(map);
    }

    return value;
  }

  // List.copyOf and Map.copyOf would reject the nulls that JSON arrays and objects may hold
  private static List<@Nullable Object> toUnmodifiableList(final List<?> list) {
    final List<@Nullable Object> copy = new ArrayList<>(list.size());
    for (final @Nullable Object element : list) {
      final @Nullable Object unmodifiable = toUnmodifiable(element);
      copy.add(unmodifiable);
    }

    return Collections.unmodifiableList(copy);
  }

  private static Map<@Nullable Object, @Nullable Object> toUnmodifiableMap(final Map<?, ?> map) {
    final Map<@Nullable Object, @Nullable Object> copy = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : map.entrySet()) {
      final @Nullable Object key = entry.getKey();
      final @Nullable Object entryValue = entry.getValue();
      final @Nullable Object unmodifiable = toUnmodifiable(entryValue);
      copy.put(key, unmodifiable);
    }

    return Collections.unmodifiableMap(copy);
  }
}
