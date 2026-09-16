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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Describes an image drawn into the chat, for use with the chat image and chat result.
 *
 * <p>Every pixel is drawn as the configured character in the color of the pixel, and every row of pixels becomes
 * one line of chat. The default chat window shows about 20 lines of 50 full block characters, so larger images
 * only fit when players enlarge their chat.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 */
public class ChatConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int chatWidth;
  private final int chatHeight;

  private ChatConfiguration(final Builder<?> builder, final Collection<UUID> viewers, final String character) {
    this.viewers = viewers;
    this.character = character;
    this.chatWidth = builder.chatWidth;
    this.chatHeight = builder.chatHeight;
  }

  /**
   * Gets the players who see the chat image.
   *
   * @return the UUIDs of the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the text drawn for every pixel, usually a single character such as {@code █}.
   *
   * @return the pixel text
   * @see me.brandonli.mcav.bukkit.media.result.Characters
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the width of the image in characters.
   *
   * @return the width in characters
   */
  public int getChatWidth() {
    return this.chatWidth;
  }

  /**
   * Gets the height of the image in lines.
   *
   * @return the height in lines
   */
  public int getChatHeight() {
    return this.chatHeight;
  }

  /**
   * The builder returned by {@link #builder()}.
   */
  public static final class ChatResultBuilder extends Builder<ChatResultBuilder> {

    ChatResultBuilder() {
      // created through ChatConfiguration.builder()
    }

    /**
     * Returns this builder with its concrete type.
     *
     * @return this builder
     */
    @Override
    protected ChatResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder for a chat configuration.
   *
   * @return a new builder
   */
  public static Builder<?> builder() {
    return new ChatResultBuilder();
  }

  /**
   * Builds chat configurations. Every value is required.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private @MonotonicNonNull Collection<UUID> viewers;
    private @MonotonicNonNull String character;
    private int chatWidth;
    private int chatHeight;

    Builder() {
      // only subclassed inside this class
    }

    /**
     * Returns this builder with its concrete type, so the setters can be chained.
     *
     * @return this builder
     */
    abstract T self();

    /**
     * Sets the players who see the chat image. The collection is not copied, see {@link ChatConfiguration}.
     *
     * @param viewers the UUIDs of the viewers
     * @return this builder
     * @throws NullPointerException if the viewers are null
     */
    public T viewers(final Collection<UUID> viewers) {
      Preconditions.checkNotNull(viewers, "Viewers must not be null");
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the text drawn for every pixel.
     *
     * @param character the pixel text, usually a single character such as {@code █}
     * @return this builder
     * @throws NullPointerException if the character is null
     */
    public T character(final String character) {
      Preconditions.checkNotNull(character, "Character must not be null");
      this.character = character;
      return this.self();
    }

    /**
     * Sets the width of the image in characters.
     *
     * @param chatWidth the width in characters, which must be positive
     * @return this builder
     */
    public T chatWidth(final int chatWidth) {
      this.chatWidth = chatWidth;
      return this.self();
    }

    /**
     * Sets the height of the image in lines.
     *
     * @param chatHeight the height in lines, which must be positive
     * @return this builder
     */
    public T chatHeight(final int chatHeight) {
      this.chatHeight = chatHeight;
      return this.self();
    }

    /**
     * Builds the chat configuration.
     *
     * @return the chat configuration
     * @throws IllegalArgumentException if a size is not positive or the character is empty
     * @throws NullPointerException     if the viewers or the character were not set
     */
    public ChatConfiguration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");
      final String configuredCharacter = Preconditions.checkNotNull(this.character, "Character must be set");

      Preconditions.checkArgument(!configuredCharacter.isEmpty(), "Character must not be empty");
      Preconditions.checkArgument(this.chatWidth > 0, "Chat width must be positive");
      Preconditions.checkArgument(this.chatHeight > 0, "Chat height must be positive");

      return new ChatConfiguration(this, configuredViewers, configuredCharacter);
    }
  }
}
