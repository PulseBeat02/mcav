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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

final class MCV2SettingsTest {

  @Test
  void keepsTheTwoLivePresetsAsData() {
    assertEquals(new Settings(72, false), Settings.DEFAULT);
    assertEquals(new Settings(55, true), Settings.FAST);
  }

  @Test
  void copiesWithOneValueChanged() {
    final Settings settings = new Settings(72, true);
    assertEquals(new Settings(0, true), settings.withLambda(0));
  }

  @Test
  void refusesInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(-1));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withLambda(Double.NaN));
  }
}
