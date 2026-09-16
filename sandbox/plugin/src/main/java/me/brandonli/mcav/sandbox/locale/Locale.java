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

import com.google.common.base.Preconditions;
import java.util.Map;

/**
 * The languages the messages of the plugin are available in, chosen with the {@code language} option of
 * {@code config.yml}.
 *
 * <p>Each language has a message file named {@code locale/mcav_<language>.properties} in lowercase, such as
 * {@code locale/mcav_en_us.properties}, which is copied into the data folder of the plugin on first start so server
 * owners can edit the messages.
 */
public enum Locale {
  /**
   * American English, read from {@code locale/mcav_en_us.properties}. This is the default language and the one
   * used when the configured language is not recognized.
   */
  EN_US;

  private static final Map<String, Locale> LOOKUP_TABLE = Map.of("EN_US", EN_US);

  /**
   * Finds the language for the {@code language} option of {@code config.yml}. Case does not matter, so
   * {@code en_us} and {@code EN_US} both select {@link #EN_US}, whatever the default locale of the server is.
   *
   * @param locale the configured language, such as {@code EN_US}
   * @return the language, or {@link #EN_US} if the value names no supported language
   * @throws NullPointerException if the value is {@code null}
   */
  public static Locale fromString(final String locale) {
    Preconditions.checkNotNull(locale, "Locale must not be null");
    final String upperCaseName = locale.toUpperCase(java.util.Locale.ROOT);
    return LOOKUP_TABLE.getOrDefault(upperCaseName, EN_US);
  }
}
