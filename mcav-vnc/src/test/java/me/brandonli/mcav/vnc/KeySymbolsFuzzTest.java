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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.Reader;
import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Tag;

// Fuzzes the keysym table reader with arbitrary text: a table is either read the same way every time or refused with
// a VncException of MCAV's own, never with a Gson or number parsing exception.
@Tag("fuzz")
final class KeySymbolsFuzzTest {

  private static final String MESSAGE_END = "!";

  @FuzzTest(maxDuration = "30s")
  void shouldReadATableOrRefuseItWithAVncException(final FuzzedDataProvider data) {
    final String text = data.consumeRemainingAsString();
    final Map<String, Integer> symbols;
    try {
      symbols = this.parse(text);
    } catch (final VncException exception) {
      final String message = exception.getMessage();
      final boolean ownMessage = message.endsWith(MESSAGE_END);
      assertTrue(ownMessage, message);
      return;
    }

    final Map<String, Integer> again = this.parse(text);
    assertEquals(symbols, again, "the same table reads the same way twice");
  }

  private Map<String, Integer> parse(final String text) {
    final Reader reader = new StringReader(text);
    return KeySymbols.parse(reader);
  }
}
