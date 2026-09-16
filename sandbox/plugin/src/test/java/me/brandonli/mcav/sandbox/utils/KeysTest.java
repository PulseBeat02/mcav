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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Keys}.
 */
final class KeysTest {

  private static String format(final NamespacedKey key) {
    return key.asString();
  }

  @Test
  void namesEveryKeyInThePluginNamespace() {
    final String map = format(Keys.MAP_KEY);
    final String first = format(Keys.FIRST_MAP_KEY);
    final String last = format(Keys.LAST_MAP_KEY);
    assertEquals("mcav", Keys.NAMESPACE);
    assertEquals("mcav:map", map);
    assertEquals("mcav:first_map", first);
    assertEquals("mcav:second_map", last);
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(Keys.class);
  }
}
