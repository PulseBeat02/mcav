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
package me.brandonli.mcav.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.Map;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link GsonProvider}.
 */
final class GsonProviderTest {

  @Test
  void sharesOneInstance() {
    final Gson first = GsonProvider.getSimple();
    final Gson second = GsonProvider.getSimple();
    assertNotNull(first);
    assertSame(first, second);
  }

  @Test
  void usesTheGsonDefaults() {
    final Gson gson = GsonProvider.getSimple();
    final boolean serializesNulls = gson.serializeNulls();
    final boolean escapesHtml = gson.htmlSafe();
    final Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", "a<b");
    values.put("missing", null);
    final String json = gson.toJson(values);
    assertFalse(serializesNulls);
    assertTrue(escapesHtml);
    assertEquals("{\"name\":\"a\\u003cb\"}", json);
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(GsonProvider.class);
  }
}
