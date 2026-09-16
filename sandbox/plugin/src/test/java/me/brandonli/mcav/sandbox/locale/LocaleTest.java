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
package me.brandonli.mcav.sandbox.locale;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link Locale}.
 */
final class LocaleTest {

  @ParameterizedTest
  @ValueSource(strings = { "EN_US", "en_us", "En_Us" })
  void findsTheLocaleIgnoringCase(final String name) {
    final Locale locale = Locale.fromString(name);
    assertSame(Locale.EN_US, locale);
  }

  @ParameterizedTest
  @ValueSource(strings = { "FR_FR", "", "english" })
  void fallsBackToEnglishForUnknownLanguages(final String name) {
    final Locale locale = Locale.fromString(name);
    assertSame(Locale.EN_US, locale);
  }

  @Test
  void refusesANullName() {
    assertThrows(NullPointerException.class, () -> Locale.fromString(null));
  }
}
