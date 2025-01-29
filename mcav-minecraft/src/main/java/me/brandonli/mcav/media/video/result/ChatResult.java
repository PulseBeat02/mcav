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
package me.brandonli.mcav.media.video.result;

import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.combined.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * ChatResult is an implementation of the VideoFilter interface that processes
 * video frame data by resizing it to specified dimensions and transforming it
 * into a chat-style message for display. The result is broadcasted to a set
 * of viewers identified by their UUIDs.
 * <p>
 * This class utilizes a builder pattern through the nested {@link Builder}
 * base class for constructing immutable instances. The builder ensures that
 * all required fields are provided and validates their input.
 * <p>
 * Key responsibilities of this class include:
 * - Resizing frame data to the specified width and height.
 * - Generating a chat component message from the frame.
 * - Sending the message as a packet to viewers.
 * <p>
 * Construction requires providing the following parameters:
 * - A collection of viewer UUIDs to send the chat message to.
 * - A character to be used in the generated chat message.
 * - Dimensions for the chat message via width and height.
 * <p>
 * Any attempt to build an incomplete or incorrectly configured ChatResult
 * instance will result in a validation exception.
 */
public class ChatResult implements VideoFilter {

  private final Collection<UUID> viewers;
  private final String character;
  private final int chatWdith;
  private final int chatHeight;

  private ChatResult(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.chatWdith = builder.chatWidth;
    this.chatHeight = builder.chatHeight;
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
    public VideoFilter build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkArgument(this.chatWidth > 0, "Chat width must be positive");
      Preconditions.checkArgument(this.chatHeight > 0, "Chat height must be positive");
      return new ChatResult(this);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    data.resize(this.chatWdith, this.chatHeight);
    final int[] resizedData = data.getAllPixels();
    final Component msg = ChatUtils.createChatComponent(resizedData, this.character, this.chatWdith, this.chatHeight);
    @SuppressWarnings("deprecation")
    final ChatMessageLegacy chatMessageLegacy = new ChatMessageLegacy(msg, ChatTypes.GAME_INFO);
    final WrapperPlayServerChatMessage packet = new WrapperPlayServerChatMessage(chatMessageLegacy);
    PacketUtils.sendPackets(this.viewers, packet);
  }
}
