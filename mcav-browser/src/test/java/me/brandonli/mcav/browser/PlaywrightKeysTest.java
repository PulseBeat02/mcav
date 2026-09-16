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

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Set;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.PlayerException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

/**
 * Tests {@link PlaywrightKeys}.
 */
final class PlaywrightKeysTest {

  @Test
  void recognizesTheKeyNamesOfPlaywright() {
    final boolean enter = PlaywrightKeys.isSpecialKey("Enter");
    final boolean arrow = PlaywrightKeys.isSpecialKey("ArrowLeft");
    final boolean function = PlaywrightKeys.isSpecialKey("F5");
    final boolean space = PlaywrightKeys.isSpecialKey(" ");
    final boolean letter = PlaywrightKeys.isSpecialKey("a");
    final boolean word = PlaywrightKeys.isSpecialKey("hello");
    final boolean lowerCase = PlaywrightKeys.isSpecialKey("enter");
    assertTrue(enter);
    assertTrue(arrow);
    assertTrue(function);
    assertTrue(space);
    assertFalse(letter);
    assertFalse(word);
    assertFalse(lowerCase);
  }

  @Test
  void translatesKeyNamesToSeleniumKeys() {
    final String enter = PlaywrightKeys.toSeleniumKeys("Enter");
    final String backspace = PlaywrightKeys.toSeleniumKeys("Backspace");
    final String space = PlaywrightKeys.toSeleniumKeys(" ");
    final String functionKey = PlaywrightKeys.toSeleniumKeys("F12");
    final String enterKey = Keys.ENTER.toString();
    final String backspaceKey = Keys.BACK_SPACE.toString();
    final String spaceKey = Keys.SPACE.toString();
    final String f12Key = Keys.F12.toString();
    assertEquals(enterKey, enter);
    assertEquals(backspaceKey, backspace);
    assertEquals(spaceKey, space);
    assertEquals(f12Key, functionKey);
  }

  @Test
  void passesOtherTextToSeleniumUnchanged() {
    final String text = PlaywrightKeys.toSeleniumKeys("hello world");
    final String empty = PlaywrightKeys.toSeleniumKeys("");
    assertEquals("hello world", text);
    assertEquals("", empty);
  }

  @Test
  void rejectsNullText() {
    assertThrows(NullPointerException.class, () -> PlaywrightKeys.isSpecialKey(null));
    assertThrows(NullPointerException.class, () -> PlaywrightKeys.toSeleniumKeys(null));
  }

  @Test
  void parsesKeyListsIntoUnmodifiableSets() {
    final Reader reader = new StringReader("[\"Enter\", \"Tab\", \"Enter\"]");
    final Set<String> keys = PlaywrightKeys.parse(reader);
    final Set<String> expected = Set.of("Enter", "Tab");
    assertEquals(expected, keys);
    assertThrows(UnsupportedOperationException.class, keys::clear);
  }

  @Test
  void rejectsEmptyKeyLists() {
    final Reader reader = new StringReader("");
    final PlayerException exception = assertThrows(PlayerException.class, () -> PlaywrightKeys.parse(reader));
    final String message = exception.getMessage();
    final boolean mentionsEmpty = message.contains("empty");
    assertTrue(mentionsEmpty, message);
  }

  @Test
  void wrapsReadFailures() {
    final PlayerException exception = assertThrows(PlayerException.class, PlaywrightKeysTest::parseReaderThatFailsToClose);
    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(PlaywrightKeys.class);
  }

  /**
   * Parses a valid key list from a reader that fails to close; {@link PlaywrightKeys#parse(Reader)} closes it, so
   * the parse fails.
   */
  private static void parseReaderThatFailsToClose() {
    final Reader reader = new FailingCloseReader("[]");
    PlaywrightKeys.parse(reader);
  }

  /**
   * A reader of a string that fails to close.
   */
  private static final class FailingCloseReader extends Reader {

    private final StringReader content;

    FailingCloseReader(final String text) {
      this.content = new StringReader(text);
    }

    @Override
    public int read(final char@NonNull[] buffer, final int offset, final int length) throws IOException {
      return this.content.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      throw new IOException("close failed");
    }
  }
}
