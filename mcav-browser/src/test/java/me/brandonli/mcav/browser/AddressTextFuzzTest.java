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

/**
 * Fuzzes the description of the addresses a page can make the browser report. It never throws; whatever follows a
 * query or fragment mark in the description is the marker and nothing else, a user name is never shown, and an address
 * without a host is shown by its scheme only.
 */
@Tag("fuzz")
final class AddressTextFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void neverShowsTheUserTheQueryOrTheFragment(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final String described = AddressText.describe(address);
    final int mark = indexOfAny(described, "?#");
    if (mark >= 0) {
      assertEquals(AddressText.HIDDEN, described.substring(mark + 1), described);
    }
    final int user = described.indexOf('@');
    final int separator = described.indexOf("://");
    // an @ in the authority stands behind the marker; one later belongs to the path
    final int authorityEnd = separator < 0 ? -1 : indexOfAny(described.substring(separator + 3), "/?#");
    if (user >= 0 && separator >= 0 && (authorityEnd < 0 || user < separator + 3 + authorityEnd) && user > separator) {
      assertTrue(described.startsWith(AddressText.HIDDEN + "@", separator + 3), described);
    }
    if (separator < 0) {
      assertTrue(described.endsWith(AddressText.HIDDEN), described);
    }
  }

  private static int indexOfAny(final String text, final String characters) {
    for (int index = 0; index < text.length(); index++) {
      if (characters.indexOf(text.charAt(index)) >= 0) {
        return index;
      }
    }
    return -1;
  }
}
