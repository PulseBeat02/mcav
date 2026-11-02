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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Set;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class SpecialKeysTest {

  @Test
  void shouldPressTheW3cKeyNames() {
    for (final String key : new String[] { "Enter", "Backspace", "ArrowLeft", "PageDown", " ", "F12", "MediaPlayPause" }) {
      final boolean namedKeySpecial = SpecialKeys.isSpecialKey(key);
      assertTrue(namedKeySpecial, key);
    }

    final boolean lowerCaseEnterSpecial = SpecialKeys.isSpecialKey("enter");
    assertFalse(lowerCaseEnterSpecial);

    final boolean plainWordSpecial = SpecialKeys.isSpecialKey("hello");
    assertFalse(plainWordSpecial);
    assertThrows(NullPointerException.class, () -> SpecialKeys.isSpecialKey(null));
  }

  @Test
  void shouldReadTheListFromJsonIntoAnUnmodifiableSet() {
    final Set<String> keys = SpecialKeys.parse(new StringReader("[\"A\", \"B\", \"A\"]"));
    assertEquals(Set.of("A", "B"), keys);
    assertThrows(UnsupportedOperationException.class, keys::clear);
  }

  @Test
  void shouldRefuseAnEmptyList() {
    final BrowserException failure = assertThrows(BrowserException.class, () -> SpecialKeys.parse(new StringReader("")));
    final String message = failure.getMessage();
    assertEquals("The key list resource mcav/browser/keybinds.json is empty!", message);
  }

  @Test
  void shouldRefuseAnEmptyJsonList() {
    final BrowserException failure = assertThrows(BrowserException.class, () -> SpecialKeys.parse(new StringReader("[]")));
    final String message = failure.getMessage();
    assertEquals("The key list resource mcav/browser/keybinds.json is empty!", message);
  }

  @Test
  void shouldRefuseAListThatCannotBeClosed() {
    final Reader throwing = new FilterReader(new StringReader("[\"A\"]")) {
      @Override
      public void close() throws IOException {
        throw new IOException("disk gone");
      }
    };
    final BrowserException failure = assertThrows(BrowserException.class, () -> SpecialKeys.parse(throwing));
    final String message = failure.getMessage();
    assertEquals("The key list resource could not be read (disk gone)!", message);

    final Throwable cause = failure.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldNotInstantiateTheKeys() {
    UtilityClassAssertions.assertNotInstantiable(SpecialKeys.class);
  }
}
