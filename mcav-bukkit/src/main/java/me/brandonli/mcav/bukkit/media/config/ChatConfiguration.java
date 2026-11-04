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
package me.brandonli.mcav.bukkit.media.config;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.image.ChatImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.bukkit.media.result.ChatResult;

/**
 * Describes an image drawn into the chat, for use with {@link ChatImage} and {@link ChatResult}.
 *
 * <p>Every pixel is drawn as the configured character in the color of the pixel, and every row of pixels becomes
 * one line of chat. The default chat window shows about 20 lines of 50 full block characters, so larger images
 * only fit when players enlarge their chat.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 *
 * <p>This class is immutable and thread-safe, except for the viewer collection, which it shares with the caller instead
 * of copying.
 *
 * {@snippet :
 * final int chatWidth = 16;
 * final int chatHeight = 16;
 * final ChatConfigurationBuilder builder = ChatConfiguration.builder();
 * final ChatConfiguration configuration = builder
 *   .viewers(viewers)
 *   .character(Characters.BLACK_SQUARE)
 *   .chatWidth(chatWidth)
 *   .chatHeight(chatHeight)
 *   .build();
 * }
 *
 * @since 1.0.0
 */
public final class ChatConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int chatWidth;
  private final int chatHeight;

  ChatConfiguration(final Collection<UUID> viewers, final String character, final int chatWidth, final int chatHeight) {
    this.viewers = viewers;
    this.character = character;
    this.chatWidth = chatWidth;
    this.chatHeight = chatHeight;
  }

  /**
   * Creates a new builder for a chat configuration.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static ChatConfigurationBuilder builder() {
    return new DefaultChatConfigurationBuilder();
  }

  /**
   * Returns the players who see the chat image.
   *
   * @return the original mutable-or-immutable collection supplied to the builder, without a copy; use a
   *         concurrent collection if another thread changes membership during rendering
   * @since 1.0.0
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Returns the text drawn for every pixel, usually a single character such as {@code █}.
   *
   * @return the pixel text
   * @see Characters
   * @since 1.0.0
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Returns the width of the image in characters.
   *
   * @return the width in characters
   * @since 1.0.0
   */
  public int getChatWidth() {
    return this.chatWidth;
  }

  /**
   * Returns the height of the image in lines.
   *
   * @return the height in lines
   * @since 1.0.0
   */
  public int getChatHeight() {
    return this.chatHeight;
  }
}
