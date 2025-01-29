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
package me.brandonli.mcav.media.result;

import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessageLegacy;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.media.config.ChatConfiguration;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;

/**
 * The ChatResult class implements the {@link VideoFilter} interface and provides
 * the functionality to apply a chat-based transformation to video data. It uses
 * the configuration provided by {@link ChatConfiguration} to customize the
 * chat display and interaction settings.
 * <p>
 * This class processes video frames by resizing them according to the chat
 * configuration dimensions and generating a chat component based on the frame
 * data. The chat component is then sent to the viewers provided by the configuration.
 * <p>
 * Responsibilities of this class include:
 * - Resizing video frames to the specified chat width and height
 * - Creating chat components from processed frames
 * - Sending chat messages to the configured set of viewers
 */
public class ChatResult implements VideoFilter {

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
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    final int chatWdith = this.configuration.getChatWdith();
    final int chatHeight = this.configuration.getChatHeight();
    final String character = this.configuration.getCharacter();
    final Collection<UUID> viewers = this.configuration.getViewers();
    data.resize(chatWdith, chatHeight);
    final int[] resizedData = data.getAllPixels();
    final Component msg = ChatUtils.createChatComponent(resizedData, character, chatWdith, chatHeight);
    @SuppressWarnings("deprecation")
    final ChatMessageLegacy chatMessageLegacy = new ChatMessageLegacy(msg, ChatTypes.GAME_INFO);
    final WrapperPlayServerChatMessage packet = new WrapperPlayServerChatMessage(chatMessageLegacy);
    PacketUtils.sendPackets(viewers, packet);
  }
}
