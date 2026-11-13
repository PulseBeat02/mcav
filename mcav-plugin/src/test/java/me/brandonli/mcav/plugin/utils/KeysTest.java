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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

final class KeysTest {

  @Test
  void shouldNameEveryKeyInThePluginNamespace() {
    final String map = this.format(Keys.MAP_KEY);
    final String first = this.format(Keys.FIRST_MAP_KEY);
    final String last = this.format(Keys.LAST_MAP_KEY);
    final String screen = this.format(Keys.SCREEN_KEY);
    assertEquals("mcav", Keys.NAMESPACE);
    assertEquals("mcav:map", map);
    assertEquals("mcav:first_map", first);
    assertEquals("mcav:second_map", last);
    assertEquals("mcav:screen", screen);
  }

  private String format(final NamespacedKey key) {
    return key.asString();
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(Keys.class);
  }
}
