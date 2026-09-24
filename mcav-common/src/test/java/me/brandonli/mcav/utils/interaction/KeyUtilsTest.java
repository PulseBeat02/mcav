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

/**
 * Tests {@link KeyUtils}.
 */
final class KeyUtilsTest {

  @Test
  void leavesPlainTextUntouched() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("hello world");
    assertEquals("hello world", replaced);
  }

  @Test
  void replacesKeyNamesInAnyCase() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("a{ENTER}b{tab}");
    final String enter = KeyCode.ENTER.asString();
    final String tab = KeyCode.TAB.asString();
    final String expected = "a" + enter + "b" + tab;
    assertEquals(expected, replaced);
  }

  @Test
  void expandsAdjacentKeyNames() {
    final String actual = KeyUtils.replaceKeysWithKeyCodes("{ENTER}{TAB}");
    final String enter = KeyCode.ENTER.asString();
    final String tab = KeyCode.TAB.asString();
    final String expected = enter + tab;
    assertEquals(expected, actual);
  }

  @Test
  void turnsDoubledBracesIntoOneBrace() {
    final String replaced = KeyUtils.replaceKeysWithKeyCodes("{{ENTER}");
    assertEquals("{ENTER}", replaced);
  }

  @Test
  void keepsBracesThatDoNotNameAKey() {
    final String unknown = KeyUtils.replaceKeysWithKeyCodes("{nope}");
    final String unclosed = KeyUtils.replaceKeysWithKeyCodes("x{ENTER");
    final String trailing = KeyUtils.replaceKeysWithKeyCodes("end{");
    assertEquals("{nope}", unknown);
    assertEquals("x{ENTER", unclosed);
    assertEquals("end{", trailing);
  }

  @Test
  void rejectsNullInput() {
    assertThrows(NullPointerException.class, () -> KeyUtils.replaceKeysWithKeyCodes(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(KeyUtils.class);
  }
}
