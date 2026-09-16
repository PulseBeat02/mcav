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
package me.brandonli.mcav.bukkit.media.result;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * A video filter that displays every frame as a chat message. Every frame is sent as a new message that pushes
 * the previous frame up, so the chat height of the viewers should match the configured height. Packets are sent
 * directly, so the filter may run on any thread.
 */
public class ChatResult implements FunctionalVideoFilter {

  private final ChatConfiguration configuration;

  /**
   * Constructs a new {@code ChatResult}.
   *
   * @param configuration the configuration describing the viewers, character, and size of the chat image
   */
  public ChatResult(final ChatConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Chat configuration must not be null");
    this.configuration = configuration;
  }

  /**
   * Resizes the frame to the size of the chat and sends it to every connected viewer as one chat message.
   *
   * @param data     the frame to display, which is resized to the configured chat size
   * @param metadata the metadata of the original video
   * @return always true, because the frame is resized
   */
  @Override
  public boolean applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(data, "Frame must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    final int width = this.configuration.getChatWidth();
    final int height = this.configuration.getChatHeight();
    final ResizeFilter resize = new ResizeFilter(width, height);
    resize.applyFilter(data, metadata);

    final int[] pixels = data.getPixels();
    final String character = this.configuration.getCharacter();
    final Component message = ChatUtils.createChatComponent(pixels, character, width, height);
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(message, false);
    final Collection<UUID> viewers = this.configuration.getViewers();
    PacketUtils.sendPackets(viewers, packet);
    return true;
  }

  /**
   * Clears the chat of every viewer, so the video starts on an empty chat.
   */
  @Override
  public void start() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    ChatUtils.clearChat(viewers);
  }

  /**
   * Clears the chat of every viewer.
   */
  @Override
  public void release() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    ChatUtils.clearChat(viewers);
  }
}
