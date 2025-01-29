/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils;

import net.minecraft.network.chat.Component;
import org.bukkit.craftbukkit.util.CraftChatMessage;

/**
 * Utility class for creating and manipulating chat components with color-coded text representations.
 * This class is designed to generate components by processing data arrays and applying coloring
 * based on pixel values. It is optimized for creating visual output suitable for chat environments.
 * <p>
 * This is a non-instantiable utility class.
 */
public final class ChatUtils {

  private static final char[] CHARACTER_DICTIONARY = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

  private ChatUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static net.minecraft.network.chat.Component toComponent(final String raw) {
    return CraftChatMessage.fromStringOrNull(raw);
  }

  /**
   * Creates a single line of color-coded text in string format based on pixel values from an array.
   *
   * @param data      an array of integers representing RGB values for pixels in the image
   * @param character the character to use for building the line
   * @param width     the total width of the line in pixels
   * @param y         the vertical index or row in the data array to process
   * @return a String representing the constructed line with color-coded text
   */
  public static Component createLine(final int[] data, final String character, final int width, final int y) {
    final StringBuilder builder = new StringBuilder(width * (8 + character.length()));
    int before = -1;
    for (int x = 0; x < width; ++x) {
      final int rgb = data[width * y + x];
      if (before != rgb) {
        builder.append("§#");
        appendHexColor(builder, rgb & 0xFFFFFF);
        builder.append(character);
        before = rgb;
      } else {
        builder.append(character);
      }
    }
    final String line = builder.toString();
    return toComponent(line);
  }

  /**
   * Generates a string with color codes by mapping an array of RGB pixel data to text characters.
   *
   * @param data      an array of integers representing pixel data in RGB format
   * @param character the character to use for visual representation of each pixel
   * @param width     the width of the "image" to be represented in characters
   * @param height    the height of the "image" to be represented in characters
   * @return a String with color codes representing the visual data
   */
  public static Component createChatComponent(final int[] data, final String character, final int width, final int height) {
    final StringBuilder builder = new StringBuilder(width * height * (8 + character.length()) + height);
    int before = -1;
    for (int y = 0; y < height; ++y) {
      if (y > 0) {
        builder.append('\n');
      }
      for (int x = 0; x < width; ++x) {
        final int rgb = data[width * y + x];
        if (before != rgb) {
          builder.append("§#");
          appendHexColor(builder, rgb & 0xFFFFFF);
          builder.append(character);
          before = rgb;
        } else {
          builder.append(character);
        }
      }
    }
    final String line = builder.toString();
    return toComponent(line);
  }

  private static void appendHexColor(final StringBuilder builder, final int rgb) {
    builder.append(CHARACTER_DICTIONARY[(rgb >> 20) & 0xF]);
    builder.append(CHARACTER_DICTIONARY[(rgb >> 16) & 0xF]);
    builder.append(CHARACTER_DICTIONARY[(rgb >> 12) & 0xF]);
    builder.append(CHARACTER_DICTIONARY[(rgb >> 8) & 0xF]);
    builder.append(CHARACTER_DICTIONARY[(rgb >> 4) & 0xF]);
    builder.append(CHARACTER_DICTIONARY[rgb & 0xF]);
  }
}
