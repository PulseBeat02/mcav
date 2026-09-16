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
package me.brandonli.mcav.bukkit.media.image;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * Displays still images in the chat of the viewers. The chat is cleared before every image, so only the latest
 * image is visible. Packets are sent directly, so images may be displayed from any thread.
 */
public class ChatImage implements DisplayableImage {

  private final ChatConfiguration configuration;

  ChatImage(final ChatConfiguration configuration) {
    this.configuration = configuration;
  }

  /**
   * Clears the chat of every viewer and sends the image as one chat message. May be called from any thread.
   *
   * @param image the image to show, which is resized to the chat dimensions in place
   * @throws NullPointerException if the image is null
   */
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    final int width = this.configuration.getChatWidth();
    final int height = this.configuration.getChatHeight();
    final ResizeFilter resize = new ResizeFilter(width, height);
    resize.applyFilter(image);

    final int[] pixels = image.getPixels();
    final String character = this.configuration.getCharacter();
    final Component message = ChatUtils.createChatComponent(pixels, character, width, height);
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(message, false);

    final Collection<UUID> viewers = this.configuration.getViewers();
    ChatUtils.clearChat(viewers);
    PacketUtils.sendPackets(viewers, packet);
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
