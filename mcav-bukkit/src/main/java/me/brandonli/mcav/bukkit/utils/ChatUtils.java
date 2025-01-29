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
 * Utility class for sending components to players.
 */
public final class ChatUtils {

  private static final char[] CHARACTER_DICTIONARY = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
  private static final String CLEAR_CHAT_MESSAGE = StringUtils.repeat("\n", 100);

  private ChatUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Generates a unique colored string based on the hexadecimal representation of the given index. Meant to be
   * used for creating teams with invisible names.
   *
   * @param index an integer seed, or index
   * @return an "invisible" string that can be used for team names
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
   * Creates a raw line of text with colored characters based on the RGB values in the provided data array.
   * Each unique RGB value is represented by a Minecraft color code, and the specified character is appended after each color code.
   *
   * @param data      an array of RGB values representing pixel colors
   * @param character the character to append after each color code
   * @param width     the width of the line (number of pixels)
   * @param y         the vertical position in the data array
   * @return a string representing a single line of colored text
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
   * Creates a chat component from the given rgb pixel data, character, width, and height.
   * The component will have colors based on the RGB values in the data array.
   *
   * @param data      the pixel data as an array of RGB integers
   * @param character the character to repeat for each pixel
   * @param width     the width of the chat component
   * @param height    the height of the chat component
   * @return a Component representing the chat message
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
   * Emulates a clear chat by sending a large number of new lines to the players.
   *
   * @param viewers players to clear the chat for
   */
  public static void clearChat(final Collection<UUID> viewers) {
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.sendMessage(CLEAR_CHAT_MESSAGE);
    }
  }
}
