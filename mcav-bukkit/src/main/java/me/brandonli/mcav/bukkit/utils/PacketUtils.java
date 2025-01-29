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
package me.brandonli.mcav.bukkit.utils;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.MCAVBukkit;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Utility class for sending NMS packets to players in a Bukkit server.
 */
public final class PacketUtils {

  private static final Map<UUID, ServerGamePacketListenerImpl> PLAYER_CONNECTIONS = new ConcurrentHashMap<>();

  /**
   * Utility method only meant to be used by {@link MCAVBukkit} to initialize the packet listener. Do not
   * use this method directly.
   */
  public static void init() {
    final Plugin plugin = MCAVBukkit.getPlugin();
    final PluginManager manager = Bukkit.getPluginManager();
    final Listener listener = new EventInitializer();
    manager.registerEvents(listener, plugin);
  }

  private PacketUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Sends the specified packets to all players in the provided collection of UUIDs.
   *
   * @param viewers a collection of player UUIDs to whom the packets will be sent
   * @param packets the packets to send
   */
  public static void sendPackets(final Collection<UUID> viewers, final Packet<?>... packets) {
    for (final UUID viewer : viewers) {
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
    final Player player = requireNonNull(Bukkit.getPlayer(uuid));
    final CraftPlayer craftPlayer = (CraftPlayer) player;
    final ServerPlayer nmsPlayer = craftPlayer.getHandle();
    final ServerGamePacketListenerImpl conn = nmsPlayer.connection;
    PLAYER_CONNECTIONS.put(uuid, conn);
  }

  private static void removePlayerConnection(final UUID uuid) {
    PLAYER_CONNECTIONS.remove(uuid);
  }

  private static class EventInitializer implements Listener {

    /**
     * Creates a new player connection when a player joins the server.
     *
     * @param event the PlayerJoinEvent triggered when a player joins
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(final PlayerJoinEvent event) {
      final Player player = event.getPlayer();
      final UUID uuid = player.getUniqueId();
      addPlayerConnection(uuid);
    }

    /**
     * Removes the player connection when a player quits the server.
     *
     * @param event the PlayerQuitEvent triggered when a player quits
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(final PlayerQuitEvent event) {
      final Player player = event.getPlayer();
      final UUID uuid = player.getUniqueId();
      removePlayerConnection(uuid);
    }
  }
}
