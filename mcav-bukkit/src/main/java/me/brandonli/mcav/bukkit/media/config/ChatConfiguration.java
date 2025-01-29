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
package me.brandonli.mcav.bukkit.media.config;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;

/**
 * Represents a configuration for chat related prototypes.
 */
public class ChatConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int chatWdith;
  private final int chatHeight;

  private ChatConfiguration(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.chatWdith = builder.chatWidth;
    this.chatHeight = builder.chatHeight;
  }

  /**
   * Gets the viewers of this chat configuration.
   *
   * @return the collection of UUIDs representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the character string associated with this chat configuration.
   *
   * @return the character string, which may represent a specific
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the width of the chat
   *
   * @return the chat width
   */
  public int getChatWidth() {
    return this.chatWdith;
  }

  /**
   * Gets the height of the chat
   *
   * @return the chat height as an integer
   */
  public int getChatHeight() {
    return this.chatHeight;
  }

  /**
   * Chat configuration builder abstraction.
   */
  public static final class ChatResultBuilder extends Builder<ChatResultBuilder> {

    ChatResultBuilder() {
      // no-op
    }

    @Override
    protected ChatResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new chat configuration builder.
   *
   * @return a new chat configuration builder
   */
  public static Builder<?> builder() {
    return new ChatResultBuilder();
  }

  /**
   * Abstract builder for chat configurations.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int chatWidth;
    private int chatHeight;

    Builder() {
      // no-op
    }

    abstract T self();

    /**
     * Sets the viewers of this chat configuration.
     *
     * @param viewers the viewers to set
     * @return the builder instance for chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character string of this chat configuration.
     *
     * @param character the character value to be set
     * @return the instance of the builder for method chaining
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the chat width of this chat configuration.
     *
     * @param chatWidth the width of the chat
     * @return the builder instance for method chaining
     */
    public T chatWidth(final int chatWidth) {
      this.chatWidth = chatWidth;
      return this.self();
    }

    /**
     * Sets the chat height of this chat configuration.
     *
     * @param chatHeight the height of the chat
     * @return the builder instance for method chaining
     */
    public T chatHeight(final int chatHeight) {
      this.chatHeight = chatHeight;
      return this.self();
    }

    /**
     * Builds the chat configuration.
     *
     * @return a new instance of ChatConfiguration
     */
    public ChatConfiguration build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkArgument(this.chatWidth > 0, "Chat width must be positive");
      Preconditions.checkArgument(this.chatHeight > 0, "Chat height must be positive");
      return new ChatConfiguration(this);
    }
  }
}
