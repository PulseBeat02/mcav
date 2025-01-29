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
package me.brandonli.mcav.bukkit.media.image;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.StaticImage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * The ChatImage class provides an implementation of the DisplayableImage interface
 * to display static images in a chat environment. It leverages configurations
 * from a ChatConfiguration object to customize the representation of the image
 * using text displayed in the chat.
 * <p>
 * Responsibilities:
 * - Resizes the given StaticImage to match the dimensions specified in the ChatConfiguration.
 * - Converts the image data into a text-based representation using a specified character.
 * - Sends the resulting text image to a collection of viewers identified by their UUIDs.
 * <p>
 * This class is immutable and thread-safe as it does not modify shared state during usage.
 */
public class ChatImage implements DisplayableImage {

  private final ChatConfiguration configuration;

  ChatImage(final ChatConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void displayImage(final StaticImage data) {
    this.release();
    final int chatWdith = this.configuration.getChatWdith();
    final int chatHeight = this.configuration.getChatHeight();
    final String character = this.configuration.getCharacter();
    final Collection<UUID> viewers = this.configuration.getViewers();
    data.resize(chatWdith, chatHeight);
    final int[] resizedData = data.getAllPixels();
    final Component msg = ChatUtils.createChatComponent(resizedData, character, chatWdith, chatHeight);
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(msg, false);
    PacketUtils.sendPackets(viewers, packet);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    final Collection<UUID> viewers = this.configuration.getViewers();
    ChatUtils.clearChat(viewers);
  }
}
