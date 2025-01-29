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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/**
 * Utility class for sending packets to a collection of viewers in a Minecraft server context.
 * This class provides methods to manage and dispatch packets efficiently to players represented by their UUIDs.
 * <p>
 * PacketUtils is designed as a utility class and cannot be instantiated.
 */
public final class PacketUtils {

  private static final Map<UUID, ServerGamePacketListenerImpl> PLAYER_CONNECTIONS = new ConcurrentHashMap<>();

  private PacketUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Sends an array of packets to a collection of viewers identified by their UUIDs.
   * This method ensures that all viewers in the collection have an active connection
   * before sending the specified packets.
   *
   * @param viewers a collection of {@link UUID} objects representing the viewers to
   *                whom the packets will be sent
   * @param packets an array of packets to send to the specified viewers
   */
  public static void sendPackets(final Collection<UUID> viewers, final Packet<?>... packets) {
    for (final UUID viewer : viewers) {
      if (!PLAYER_CONNECTIONS.containsKey(viewer)) {
        addPlayerConnection(viewer);
      }
      final ServerGamePacketListenerImpl conn = PLAYER_CONNECTIONS.get(viewer);
      if (conn == null) {
        continue;
      }
      for (final Packet<?> packet : packets) {
        conn.send(packet);
      }
    }
  }

  private static void addPlayerConnection(final UUID uuid) {
    final Server server = Bukkit.getServer();
    final CraftServer craftServer = (CraftServer) server;
    final CraftPlayer player = (CraftPlayer) craftServer.getPlayer(uuid);
    if (player == null) {
      return;
    }
    final ServerPlayer nmsPlayer = player.getHandle();
    final ServerGamePacketListenerImpl conn = nmsPlayer.connection;
    PLAYER_CONNECTIONS.put(uuid, conn);
  }
}
