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
package me.brandonli.mcav.bukkit.media.result;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * Represents a filter for displaying frames as chat messages.
 */
public class ChatResult implements FunctionalVideoFilter {

  private final ChatConfiguration configuration;

  /**
   * Constructs a new instance of {@code ChatResult} using the provided configuration.
   *
   * @param configuration the {@link ChatConfiguration} object used to configure the chat result.
   *                      This object contains parameters such as viewers, character, chat width,
   *                      and chat height. Cannot be null.
   */
  public ChatResult(final ChatConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ImageBuffer data, final VideoMetadata metadata) {
    final int chatWdith = this.configuration.getChatWdith();
    final int chatHeight = this.configuration.getChatHeight();
    final String character = this.configuration.getCharacter();
    final Collection<UUID> viewers = this.configuration.getViewers();
    final ResizeFilter resize = new ResizeFilter(chatWdith, chatHeight);
    resize.applyFilter(data, metadata);
    final int[] resizedData = data.getPixels();
    final Component msg = ChatUtils.createChatComponent(resizedData, character, chatWdith, chatHeight);
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(msg, false);
    PacketUtils.sendPackets(viewers, packet);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    this.clearChatMessages();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.clearChatMessages();
  }

  private void clearChatMessages() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    ChatUtils.clearChat(viewers);
  }
}
