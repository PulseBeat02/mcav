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
package me.brandonli.mcav.utils.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class KeyUtilsTest {

  @Test
  void shouldLeavePlainTextUntouched() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("hello world");
    assertEquals("hello world", replaced);
  }

  @Test
  void shouldReplaceKeyNamesInAnyCase() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("a{ENTER}b{tab}");
    final String enter = KeyCode.ENTER.asString();
    final String tab = KeyCode.TAB.asString();
    final String expected = "a%sb%s".formatted(enter, tab);
    assertEquals(expected, replaced);
  }

  @Test
  void shouldExpandAdjacentKeyNames() {
    final String actual = KeyUtils.replaceKeysWithKeyCodes("{ENTER}{TAB}");
    final String enter = KeyCode.ENTER.asString();
    final String tab = KeyCode.TAB.asString();
    final String expected = "%s%s".formatted(enter, tab);
    assertEquals(expected, actual);
  }

  @Test
  void shouldTurnDoubledBracesIntoOneBrace() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("{{ENTER}");
    assertEquals("{ENTER}", replaced);
  }

  @Test
  void shouldKeepBracesThatDoNotNameAKey() {
    final String unknown = KeyUtils.replaceKeysWithKeyCodes("{nope}");
    final String unclosed = KeyUtils.replaceKeysWithKeyCodes("x{ENTER");
    final String trailing = KeyUtils.replaceKeysWithKeyCodes("end{");
    assertEquals("{nope}", unknown);
    assertEquals("x{ENTER", unclosed);
    assertEquals("end{", trailing);
  }

  @Test
  void shouldRejectNullInput() {
    assertThrows(NullPointerException.class, () -> KeyUtils.replaceKeysWithKeyCodes(null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(KeyUtils.class);
  }
}
