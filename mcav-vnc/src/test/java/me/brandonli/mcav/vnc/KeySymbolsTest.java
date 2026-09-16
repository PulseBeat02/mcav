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
package me.brandonli.mcav.vnc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.vnc.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link KeySymbols}.
 */
final class KeySymbolsTest {

  @Test
  void looksUpTheKeysymsOfKeyNames() {
    final OptionalInt enter = KeySymbols.lookup("Return");
    final OptionalInt escape = KeySymbols.lookup("Escape");
    final OptionalInt backSpace = KeySymbols.lookup("BackSpace");
    final int enterCode = enter.orElseThrow();
    final int escapeCode = escape.orElseThrow();
    final int backSpaceCode = backSpace.orElseThrow();
    assertEquals(0xff0d, enterCode);
    assertEquals(0xff1b, escapeCode);
    assertEquals(0xff08, backSpaceCode);
  }

  @Test
  void findsNothingForTextThatIsNotAKeyName() {
    final OptionalInt text = KeySymbols.lookup("hello world");
    final OptionalInt lowerCase = KeySymbols.lookup("return");
    final boolean textIsEmpty = text.isEmpty();
    assertTrue(textIsEmpty);
    final boolean lowerCaseIsEmpty = lowerCase.isEmpty();
    assertTrue(lowerCaseIsEmpty);
  }

  @Test
  void rejectsNullNames() {
    assertThrows(NullPointerException.class, () -> KeySymbols.lookup(null));
  }

  @Test
  void parsesHexadecimalCodesAndClosesTheReader() {
    final ClosingReader reader = new ClosingReader("{\"A\": \"0x41\", \"F1\": \"0xffbe\"}", false);
    final Map<String, Integer> symbols = KeySymbols.parse(reader);
    final int letter = symbols.get("A");
    final int function = symbols.get("F1");
    final int symbolsCount = symbols.size();
    assertEquals(2, symbolsCount);
    assertEquals(0x41, letter);
    assertEquals(0xffbe, function);

    final boolean readerIsClosed = reader.isClosed();
    assertTrue(readerIsClosed);

    // the table is shared by every player, so it must not be changeable
    final Consumer<Map<String, Integer>> addEntry = table -> table.put("B", 0x42);
    assertThrows(UnsupportedOperationException.class, () -> addEntry.accept(symbols));
  }

  @Test
  void rejectsEmptyTables() {
    final Reader reader = new StringReader("");
    final PlayerException exception = assertThrows(PlayerException.class, () -> KeySymbols.parse(reader));
    final String message = exception.getMessage();
    final boolean messageContains = message.contains("empty");
    assertTrue(messageContains, message);
  }

  @Test
  void wrapsReadFailures() {
    final PlayerException exception = assertThrows(PlayerException.class, () -> {
      final ClosingReader reader = new ClosingReader("{}", true);
      KeySymbols.parse(reader);
    });
    final Throwable cause = exception.getCause();
    final String message = exception.getMessage();
    assertInstanceOf(IOException.class, cause);
    final boolean messageContains = message.contains("close failed");
    assertTrue(messageContains, message);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(KeySymbols.class);
  }

  /**
   * A reader of a string that remembers whether it was closed and can fail to close.
   */
  private static final class ClosingReader extends Reader {

    private final StringReader content;
    private final boolean failOnClose;
    private boolean closed;

    ClosingReader(final String text, final boolean failOnClose) {
      this.content = new StringReader(text);
      this.failOnClose = failOnClose;
    }

    boolean isClosed() {
      return this.closed;
    }

    @Override
    public int read(final char@NonNull[] buffer, final int offset, final int length) throws IOException {
      return this.content.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      this.closed = true;
      this.content.close();
      if (this.failOnClose) {
        throw new IOException("close failed");
      }
    }
  }
}
