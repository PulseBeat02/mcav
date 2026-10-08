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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bukkit.craftbukkit.util.CraftChatMessage;

/**
 * Converts pixels into colored text and sends text to players.
 *
 * <p>Text is built with legacy section sign color codes, because they are the most compact way to describe a
 * color per character. A hex color is written as {@code §x§r§r§g§g§b§b}, and a color code is only emitted when
 * the color differs from the previous character, which keeps the text as short as possible.
 */
public final class ChatUtils {

  private static final char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
  private static final char COLOR_CHAR = '§';
  private static final char HEX_COLOR_MARKER = 'x';
  private static final int RGB_MASK = 0xFFFFFF;
  private static final int HEX_COLOR_LENGTH = 14;
  private static final int CHAT_HISTORY_LINES = 100;
  private static final String CLEAR_CHAT_MESSAGE = "\n".repeat(CHAT_HISTORY_LINES - 1);

  private ChatUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Generates a string of color codes that is unique for the given index. The string renders as nothing, which
   * makes it useful as an invisible, unique scoreboard entry.
   *
   * @param index a non-negative index
   * @return an invisible string that is unique for every index
   * @throws IllegalArgumentException if the index is negative
   */
  public static String getUniqueString(final int index) {
    Preconditions.checkArgument(index >= 0, "Index must be non-negative");
    final String hex = Integer.toHexString(index);
    final char[] digits = hex.toCharArray();
    final StringBuilder entry = new StringBuilder(digits.length * 2);
    for (final char digit : digits) {
      entry.append(COLOR_CHAR);
      entry.append(digit);
    }
    return entry.toString();
  }

  /**
   * Creates a single line of colored text from one row of pixels.
   *
   * <p>Pixel data is read synchronously and not retained. Alpha is ignored. The character string is inserted
   * verbatim, so formatting codes in it can affect the output; no escaping is performed.
   *
   * @param data      the pixels in ARGB format, laid out row by row; the alpha channel is ignored
   * @param character the text drawn for every pixel
   * @param width     the width of the image in pixels
   * @param row       the row to convert
   * @return the row as text with legacy section sign color codes
   * @throws IllegalArgumentException if width is nonpositive or the requested zero-based row is outside the data
   * @throws NullPointerException if {@code data} or {@code character} is null
   */
  public static String createRawLine(final int[] data, final String character, final int width, final int row) {
    Preconditions.checkNotNull(data, "Pixels must not be null");
    Preconditions.checkNotNull(character, "Character must not be null");
    Preconditions.checkArgument(width > 0, "Width must be positive");
    Preconditions.checkArgument(row >= 0 && (row + 1L) * width <= data.length, "Row is outside of the image");
    final int characterLength = character.length();
    final int capacity = width * (HEX_COLOR_LENGTH + characterLength);
    final StringBuilder builder = new StringBuilder(capacity);
    appendRow(builder, data, character, width, row);
    return builder.toString();
  }

  /**
   * Creates a chat component from the given pixels, with one line of text for every row of pixels.
   *
   * <p>Pixel data is read synchronously and not retained; extra pixels beyond the requested dimensions are
   * ignored. Character text is interpreted as legacy formatted text rather than escaped literal text.
   *
   * @param data      the pixels in ARGB format, laid out row by row; the alpha channel is ignored
   * @param character the text drawn for every pixel
   * @param width     the width of the image in pixels
   * @param height    the height of the image in pixels
   * @return a chat component showing the image
   * @throws IllegalArgumentException if either dimension is nonpositive or the image requires more pixels than supplied
   * @throws NullPointerException if {@code data} or {@code character} is null
   */
  public static Component createChatComponent(final int[] data, final String character, final int width, final int height) {
    Preconditions.checkNotNull(data, "Pixels must not be null");
    Preconditions.checkNotNull(character, "Character must not be null");
    Preconditions.checkArgument(width > 0 && height > 0, "Image must not be empty");
    Preconditions.checkArgument((long) width * height <= data.length, "Image dimensions exceed the pixel data");
    final int characterLength = character.length();
    final int capacity = width * height * (HEX_COLOR_LENGTH + characterLength) + height;
    final StringBuilder builder = new StringBuilder(capacity);
    for (int row = 0; row < height; row++) {
      if (row > 0) {
        builder.append('\n');
      }
      appendRow(builder, data, character, width, row);
    }
    final String text = builder.toString();
    final Component component = CraftChatMessage.fromStringOrNull(text, true);
    return Objects.requireNonNull(component, "Text with color codes always converts to a component");
  }

  private static void appendRow(final StringBuilder builder, final int[] data, final String character, final int width, final int row) {
    final int offset = width * row;
    int previousColor = -1;
    for (int column = 0; column < width; column++) {
      final int color = data[offset + column] & RGB_MASK;
      if (color != previousColor) {
        appendHexColor(builder, color);
        previousColor = color;
      }
      builder.append(character);
    }
  }

  private static void appendHexColor(final StringBuilder builder, final int color) {
    builder.append(COLOR_CHAR);
    builder.append(HEX_COLOR_MARKER);
    for (int shift = 20; shift >= 0; shift -= 4) {
      final int nibble = (color >> shift) & 0xF;
      final char digit = HEX_DIGITS[nibble];
      builder.append(COLOR_CHAR);
      builder.append(digit);
    }
  }

  /**
   * Clears the chat of the specified players by pushing every previous message out of the chat history. Offline
   * players are skipped. May be called from any thread.
   *
   * @param viewers the UUIDs of the players to clear the chat for
   * @throws NullPointerException if {@code viewers} is null
   */
  public static void clearChat(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    final Component message = Component.literal(CLEAR_CHAT_MESSAGE);
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(message, false);
    PacketUtils.sendPackets(viewers, packet);
  }
}
