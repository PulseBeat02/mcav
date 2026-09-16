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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link KeyCode}.
 */
final class KeyCodeTest {

  private static final char PRIVATE_USE_START = (char) 0xE000;
  private static final char PRIVATE_USE_END = (char) 0xF8FF;

  @Test
  void findsKeysByNameIgnoringCase() {
    final Optional<KeyCode> lower = KeyCode.fromName("enter");
    final Optional<KeyCode> upper = KeyCode.fromName("ARROW_LEFT");
    final Optional<KeyCode> mixed = KeyCode.fromName("Page_Down");
    final Optional<KeyCode> enter = Optional.of(KeyCode.ENTER);
    final Optional<KeyCode> arrowLeft = Optional.of(KeyCode.ARROW_LEFT);
    final Optional<KeyCode> pageDown = Optional.of(KeyCode.PAGE_DOWN);
    assertEquals(enter, lower);
    assertEquals(arrowLeft, upper);
    assertEquals(pageDown, mixed);
  }

  @Test
  void findsNothingForUnknownNames() {
    final Optional<KeyCode> unknown = KeyCode.fromName("no-such-key");
    final Optional<KeyCode> empty = KeyCode.fromName("");
    final boolean noKeyForUnknown = unknown.isEmpty();
    final boolean noKeyForEmpty = empty.isEmpty();
    assertTrue(noKeyForUnknown);
    assertTrue(noKeyForEmpty);
  }

  @Test
  void rejectsNullNames() {
    assertThrows(NullPointerException.class, () -> KeyCode.fromName(null));
  }

  @Test
  void aliasesShareTheCharacterOfTheirKey() {
    final char shift = KeyCode.SHIFT.getKeyChar();
    final char leftShift = KeyCode.LEFT_SHIFT.getKeyChar();
    final char meta = KeyCode.META.getKeyChar();
    final char command = KeyCode.COMMAND.getKeyChar();
    assertEquals(shift, leftShift);
    assertEquals(meta, command);
  }

  @Test
  void usesThePrivateUseAreaForEveryKey() {
    for (final KeyCode key : KeyCode.values()) {
      final char character = key.getKeyChar();
      final boolean privateUse = character >= PRIVATE_USE_START && character <= PRIVATE_USE_END;
      final String keyName = key.name();
      assertTrue(privateUse, keyName);
    }
  }

  @Test
  void exposesTheCharacterInEveryForm() {
    final char character = KeyCode.ENTER.getKeyChar();
    final int codePoint = KeyCode.ENTER.getCodePoint();
    final String text = KeyCode.ENTER.asString();
    final String expectedText = String.valueOf(character);
    assertEquals((char) 0xE007, character);
    assertEquals(0xE007, codePoint);
    assertEquals(expectedText, text);
  }
}
