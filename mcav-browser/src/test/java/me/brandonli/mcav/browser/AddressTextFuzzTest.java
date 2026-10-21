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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class AddressTextFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldNeverShowTheUserTheQueryOrTheFragment(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final String described = AddressText.describe(address);
    final int mark = this.indexOfAny(described, "?#");
    if (mark >= 0) {
      final String textAfterMark = described.substring(mark + 1);
      assertEquals(AddressText.HIDDEN, textAfterMark, described);
    }
    final int user = described.indexOf('@');
    final int separator = described.indexOf("://");
    final int authorityEnd = separator < 0 ? -1 : this.indexOfAny(described.substring(separator + 3), "/?#");
    if (user >= 0 && separator >= 0 && (authorityEnd < 0 || user < separator + 3 + authorityEnd) && user > separator) {
      final boolean hidesTheUserInformation = described.startsWith(AddressText.HIDDEN + "@", separator + 3);
      assertTrue(hidesTheUserInformation, described);
    }
    if (separator < 0) {
      final boolean endsHidden = described.endsWith(AddressText.HIDDEN);
      assertTrue(endsHidden, described);
    }
  }

  private int indexOfAny(final String text, final String characters) {
    for (int index = 0; index < text.length(); index++) {
      if (characters.indexOf(text.charAt(index)) >= 0) {
        return index;
      }
    }
    return -1;
  }
}
