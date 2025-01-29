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

/**
 * Utility class for handling key codes and their replacements in strings.
 */
public final class KeyUtils {

  private KeyUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Replaces key names in the input string with their corresponding key codes.
   *
   * @param input the input string containing key names
   * @return the modified string with key names replaced by key codes
   */
  public static String replaceKeysWithKeyCodes(final String input) {
    String output = input;
    for (final KeyCode key : KeyCode.values()) {
      final String keyName = key.name();
      final String keyCode = String.valueOf(key.charAt(0));
      int idx = 0;
      final StringBuilder result = new StringBuilder();
      while (idx < output.length()) {
        final int found = output.indexOf(keyName, idx);
        if (found == -1) {
          result.append(output.substring(idx));
          break;
        }
        if (found > 0 && output.charAt(found - 1) == '\\') {
          result.append(output, idx, found - 1).append(keyName);
        } else {
          result.append(output, idx, found).append(keyCode);
        }
        idx = found + keyName.length();
      }
      output = result.toString();
    }
    for (final KeyCode key : KeyCode.values()) {
      final String escaped = "\\" + key.name();
      output = output.replace(escaped, key.name());
    }
    return output;
  }
}
