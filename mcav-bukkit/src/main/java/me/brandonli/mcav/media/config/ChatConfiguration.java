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
package me.brandonli.mcav.media.config;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.result.ChatResult;

/**
 * The {@code ChatConfiguration} class represents a configuration for a chat interface.
 * It holds the parameters required to customize the chat, including the audience,
 * dimensions, and character used for representation.
 * <p>
 * This class is designed to be immutable, and instances are created using
 * its nested {@link Builder} class or its specialized {@link ChatResultBuilder}.
 * <p>
 * Responsibilities:
 * - Defines the configuration properties for chat setup.
 * - Provides accessor methods to retrieve configuration data such as viewers,
 * character, width, and height.
 * <p>
 * The builder pattern ensures that all required properties are properly set
 * before an instance of {@code ChatConfiguration} is created, while also
 * allowing for a fluent interface.
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
   * Retrieves the collection of viewers associated with this configuration.
   * Each viewer is represented by a unique UUID.
   *
   * @return a collection of UUIDs representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Retrieves the character associated with the chat configuration.
   *
   * @return the character as a string representation
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Retrieves the width of the chat configuration.
   *
   * @return the width of the chat as an integer
   */
  public int getChatWdith() {
    return this.chatWdith;
  }

  /**
   * Retrieves the height of the chat, which represents the configured vertical size
   * available for chat display in terms of units or pixels.
   *
   * @return the current chat height as an integer
   */
  public int getChatHeight() {
    return this.chatHeight;
  }

  /**
   * Builder class for constructing instances of {@link ChatResult}. This class
   * extends the abstract Builder class and provides concrete implementations
   * for the required parameters.
   */
  public static final class ChatResultBuilder extends Builder<ChatResultBuilder> {

    @Override
    protected ChatResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder instance for constructing a {@link ChatResult} object.
   *
   * @return a new instance of {@link ChatResultBuilder} for building
   */
  public static Builder<?> builder() {
    return new ChatResultBuilder();
  }

  /**
   * Abstract base class for building instances of {@link ChatResult}. This class
   * provides methods to set the required parameters for constructing a ChatResult
   * instance. The builder pattern allows for a fluent interface and ensures that
   * all necessary fields are set before building the final object.
   *
   * @param <T> the type of the builder extending this abstract class
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int chatWidth;
    private int chatHeight;

    protected abstract T self();

    /**
     * Sets the collection of UUIDs representing the viewers for this builder.
     *
     * @param viewers the collection of UUID objects that represent the viewers
     * @return the builder instance for method chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character string for the builder.
     *
     * @param character the character value to be set
     * @return the instance of the builder for method chaining
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the chat width and returns the builder instance for method chaining.
     *
     * @param chatWidth the width of the chat, must be a positive integer
     * @return the builder instance for method chaining
     */
    public T chatWidth(final int chatWidth) {
      this.chatWidth = chatWidth;
      return this.self();
    }

    /**
     * Sets the height of the chat interface.
     *
     * @param chatHeight the height of the chat in pixels; must be a positive integer
     * @return the builder instance for method chaining
     */
    public T chatHeight(final int chatHeight) {
      this.chatHeight = chatHeight;
      return this.self();
    }

    /**
     * Builds and returns a new instance of {@link VideoFilter} with the configured
     * parameters. This method validates the provided parameters to ensure the
     * required fields are set and checks the correctness of the input values.
     *
     * @return a newly constructed {@link VideoFilter} instance.
     * @throws NullPointerException     if required parameters such as viewers or character
     *                                  are null.
     * @throws IllegalArgumentException if chat width or height are non
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
