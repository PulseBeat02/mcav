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

/**
 * Writes an address of the browser the way the log of the server shows it. Addresses come from pages, which choose
 * them freely, and from the operator, and their user name and password, their query and their fragment often carry
 * secrets, such as the signature of a link or the code of a login; the log, which {@code /mcav dump} may publish, gets
 * the scheme, the host and the path only. An address without a host, such as a {@code data:} address, which holds its
 * content, is shown by its scheme only.
 */
final class AddressText {

  /**
   * What stands in place of a hidden part.
   */
  static final String HIDDEN = "<hidden>";

  private static final String AUTHORITY_START = "://";

  private AddressText() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Describes an address for the log.
   *
   * @param address the address, as the browser or the operator wrote it
   * @return the scheme, the host and the path, with the user name and password, the query and the fragment hidden
   */
  static String describe(final String address) {
    final int colon = address.indexOf(':');
    if (colon <= 0 || !isScheme(address, colon)) {
      // no scheme: it is no address at all
      return HIDDEN;
    }
    if (!address.startsWith(AUTHORITY_START, colon)) {
      // no host: the rest of the address is its content
      return address.substring(0, colon + 1) + HIDDEN;
    }
    final int authorityStart = colon + AUTHORITY_START.length();
    final int authorityEnd = indexOfAny(address, authorityStart, "/?#");
    final int user = address.lastIndexOf('@', authorityEnd - 1);
    final StringBuilder text = new StringBuilder(address.length() + HIDDEN.length());
    text.append(address, 0, authorityStart);
    if (user >= authorityStart) {
      text.append(HIDDEN).append('@');
    }
    final int hostStart = Math.max(authorityStart, user + 1);
    final int pathEnd = indexOfAny(address, authorityEnd, "?#");
    text.append(address, hostStart, pathEnd);
    if (pathEnd < address.length()) {
      text.append(address.charAt(pathEnd)).append(HIDDEN);
    }
    return text.toString();
  }

  /**
   * Checks whether the text before a colon is a scheme: a letter, then letters, digits, {@code +}, {@code .} and
   * {@code -} (RFC 3986).
   *
   * @param address the address
   * @param colon   the index of its first colon, at least 1
   * @return true if the text before the colon is a scheme
   */
  private static boolean isScheme(final String address, final int colon) {
    if (!isLetter(address.charAt(0))) {
      return false;
    }
    for (int index = 1; index < colon; index++) {
      final char character = address.charAt(index);
      final boolean digit = character >= '0' && character <= '9';
      if (!isLetter(character) && !digit && character != '+' && character != '.' && character != '-') {
        return false;
      }
    }
    return true;
  }

  private static boolean isLetter(final char character) {
    return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
  }

  private static int indexOfAny(final String text, final int from, final String characters) {
    for (int index = from; index < text.length(); index++) {
      if (characters.indexOf(text.charAt(index)) >= 0) {
        return index;
      }
    }
    return text.length();
  }
}
