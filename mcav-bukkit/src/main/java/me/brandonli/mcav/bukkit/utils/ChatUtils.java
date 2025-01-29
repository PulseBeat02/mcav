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
package me.brandonli.mcav.bukkit.utils;

import java.util.Collection;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.entity.Player;

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

  /**
   * Generates a unique colored string based on the hexadecimal representation of the given index.
   * Each character in the hexadecimal string is mapped to a corresponding color using Minecraft color codes.
   *
   * @param index an integer value whose hexadecimal representation will be used to generate the string
   * @return a string containing Minecraft-style color codes corresponding to each character in the hexadecimal representation of the index
   */
  public static String getUniqueString(final int index) {
    final StringBuilder entry = new StringBuilder();
    final String hex = Integer.toHexString(index);
    for (final char c : hex.toCharArray()) {
      @SuppressWarnings("deprecation")
      final ChatColor color = ChatColor.getByChar(c);
      entry.append(color);
    }
    return entry.toString();
  }

  /**
   * Creates a formatted Component representing a single line from an array of pixel data.
   * The method maps pixel data to corresponding characters with specific color codes
   * and converts the result into a Component.
   *
   * @param data      an array of integers representing pixel data in RGB format
   * @param character the character to use for visual representation of each pixel
   * @param width     the width of the line in characters
   * @param y         the vertical index of the line within the pixel data
   * @return a Component representing the formatted line with applied color codes
   */
  public static Component createLine(final int[] data, final String character, final int width, final int y) {
    final String line = createRawLine(data, character, width, y);
    return CraftChatMessage.fromStringOrNull(line);
  }

  /**
   * Generates a string representing a line of colored characters based on pixel data.
   * Each distinct color change in the data array is prefixed with a Minecraft-style
   * color code, followed by the specified character.
   *
   * @param data      an array of integers representing pixel data in RGB format
   * @param character the character to use for visual representation of each pixel
   * @param width     the width of the image being represented
   * @param y         the index of the row from the data array to be converted
   * @return a string containing color codes and characters representing the specified row of pixel data
   */
  public static String createRawLine(final int[] data, final String character, final int width, final int y) {
    final StringBuilder builder = new StringBuilder(width * (14 + character.length()));
    int before = -1;
    for (int x = 0; x < width; ++x) {
      final int rgb = data[width * y + x];
      if (before != rgb) {
        builder.append("§x");
        final int hexColor = rgb & 0xFFFFFF;
        for (int i = 0; i < 6; i++) {
          builder.append('§');
          builder.append(CHARACTER_DICTIONARY[(hexColor >> (20 - 4 * i)) & 0xF]);
        }
        builder.append(character);
        before = rgb;
      } else {
        builder.append(character);
      }
    }
    return builder.toString();
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
    final StringBuilder builder = new StringBuilder(width * height * (14 + character.length()) + height);
    int before = -1;
    for (int y = 0; y < height; ++y) {
      if (y > 0) {
        builder.append('\n');
      }
      for (int x = 0; x < width; ++x) {
        final int rgb = data[width * y + x];
        if (before != rgb) {
          builder.append("§x");
          final int hexColor = rgb & 0xFFFFFF;
          for (int i = 0; i < 6; i++) {
            builder.append('§');
            builder.append(CHARACTER_DICTIONARY[(hexColor >> (20 - 4 * i)) & 0xF]);
          }
          builder.append(character);
          before = rgb;
        } else {
          builder.append(character);
        }
      }
    }
    final String line = builder.toString();
    return CraftChatMessage.fromStringOrNull(line, true);
  }

  /**
   * Clears the chat for a collection of viewers by sending a large number of newline characters
   * to simulate an empty chat screen.
   *
   * @param viewers a collection of {@link UUID} objects representing the players whose chat
   *                will be cleared
   */
  public static void clearChat(final Collection<UUID> viewers) {
    final String repeated = StringUtils.repeat("\n", 100);
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.sendMessage(repeated);
    }
  }
}
