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

import com.google.common.base.Preconditions;
import java.util.Optional;

/**
 * Helpers for typing text that contains special keys.
 *
 * <p>Users write special keys by name, such as {@code Hello{ENTER}}, and {@link #replaceKeysWithKeyCodes(String)}
 * turns every {@code {NAME}} into the character of the corresponding {@link KeyCode}. A literal
 * <code>&#123;</code> is written as <code>&#123;&#123;</code>.
 */
public final class KeyUtils {

  private static final char OPEN = '{';
  private static final char CLOSE = '}';

  private KeyUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Replaces every {@code {NAME}} in the text with the character of the {@link KeyCode} of that name. Names that
   * do not match a key are left untouched, and <code>&#123;&#123;</code> produces a single <code>&#123;</code>.
   *
   * @param input the text with key names
   * @return the text with key characters
   */
  public static String replaceKeysWithKeyCodes(final String input) {
    Preconditions.checkNotNull(input, "Input must not be null");
    final int length = input.length();
    final StringBuilder output = new StringBuilder(length);
    int index = 0;
    while (index < length) {
      index = appendNext(input, index, output);
    }
    return output.toString();
  }

  /**
   * Appends the character, escaped brace or key character that starts at the index to the output.
   *
   * @return the index just past what was consumed
   */
  private static int appendNext(final String input, final int index, final StringBuilder output) {
    final char current = input.charAt(index);
    if (current != OPEN) {
      output.append(current);
      return index + 1;
    }
    final int next = index + 1;
    final boolean escaped = next < input.length() && input.charAt(next) == OPEN;
    if (escaped) {
      output.append(OPEN);
      return index + 2;
    }
    return appendKey(input, index, output);
  }

  /**
   * Appends the character of the key whose name is enclosed in braces at the index, or the opening brace itself
   * if the braces do not enclose the name of a key.
   *
   * @return the index just past what was consumed
   */
  private static int appendKey(final String input, final int index, final StringBuilder output) {
    final int close = input.indexOf(CLOSE, index + 1);
    if (close < 0) {
      output.append(OPEN);
      return index + 1;
    }
    final String name = input.substring(index + 1, close);
    final Optional<KeyCode> key = KeyCode.fromName(name);
    if (key.isEmpty()) {
      output.append(OPEN);
      return index + 1;
    }
    final KeyCode code = key.get();
    final char character = code.getKeyChar();
    output.append(character);
    return close + 1;
  }
}
