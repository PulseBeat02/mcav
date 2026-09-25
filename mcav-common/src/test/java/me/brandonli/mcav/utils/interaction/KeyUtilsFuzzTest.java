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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the key names players type into the chat of a browser or virtual machine screen: whatever the text, the
 * replacement ends, never makes the text longer, since a key name and an escaped brace both become one character, and
 * leaves a text without braces as it is. The seeds are the texts of the tests of {@link KeyUtils}.
 */
@Tag("fuzz")
final class KeyUtilsFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void replacesKeyNamesWithoutGrowingTheText(final byte[] input) {
    final String typed = new String(input, StandardCharsets.UTF_8);

    final String replaced = KeyUtils.replaceKeysWithKeyCodes(typed);

    final int typedLength = typed.length();
    final int replacedLength = replaced.length();
    assertTrue(replacedLength <= typedLength, () -> "'" + typed + "' grew to '" + replaced + "'");
    final boolean braces = typed.indexOf('{') >= 0;
    if (!braces) {
      assertEquals(typed, replaced, "a text without braces stays as it is");
    }
  }
}
