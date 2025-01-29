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
package me.brandonli.mcav.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Utility class for sending packets to a collection of viewers in a Minecraft server context.
 * This class provides methods to manage and dispatch packets efficiently to players represented by their UUIDs.
 * <p>
 * PacketUtils is designed as a utility class and cannot be instantiated.
 */
public final class PacketUtils {

  private PacketUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Sends a collection of packets to a specified group of viewers.
   * Utilizes the PacketEvents API to retrieve player channels and send the designated packets.
   *
   * @param viewers a {@code Collection} of {@code UUID} representing the viewers to whom the packets will be sent
   * @param packets an array of {@code PacketWrapper<?>} representing the packets to be transmitted to each viewer
   */
  public static void sendPackets(final Collection<UUID> viewers, final PacketWrapper<?>... packets) {
    final PacketEventsAPI<?> api = PacketEvents.getAPI();
    final PlayerManager manager = api.getPlayerManager();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      for (final PacketWrapper<?> packet : packets) {
        manager.sendPacket(player, packet);
      }
    }
  }
}
