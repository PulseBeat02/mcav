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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Set;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;

class SpecialKeysTest {

  @Test
  void theW3cKeyNamesArePressed() {
    for (final String key : new String[] { "Enter", "Backspace", "ArrowLeft", "PageDown", " ", "F12", "MediaPlayPause" }) {
      assertTrue(SpecialKeys.isSpecialKey(key), key);
    }
    assertFalse(SpecialKeys.isSpecialKey("enter"));
    assertFalse(SpecialKeys.isSpecialKey("hello"));
    assertThrows(NullPointerException.class, () -> SpecialKeys.isSpecialKey(null));
  }

  @Test
  void theListIsReadFromJson() {
    assertEquals(Set.of("A", "B"), SpecialKeys.parse(new StringReader("[\"A\", \"B\"]")));
  }

  @Test
  void anEmptyListIsRefused() {
    final PlayerException failure = assertThrows(PlayerException.class, () -> SpecialKeys.parse(new StringReader("")));
    assertEquals("The key list resource keybinds.json is empty", failure.getMessage());
  }

  @Test
  void aListThatCannotBeClosedIsRefused() {
    final Reader throwing = new FilterReader(new StringReader("[\"A\"]")) {
      @Override
      public void close() throws IOException {
        throw new IOException("disk gone");
      }
    };
    final PlayerException failure = assertThrows(PlayerException.class, () -> SpecialKeys.parse(throwing));
    assertEquals("Failed to read the key list resource: disk gone", failure.getMessage());
  }

  @Test
  void theKeysAreNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(SpecialKeys.class);
  }
}
