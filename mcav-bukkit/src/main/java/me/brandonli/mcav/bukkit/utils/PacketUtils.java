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
package me.brandonli.mcav.bukkit.utils;

import com.google.common.base.Preconditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.epoll.EpollChannelOption;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.BukkitModule;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Sends packets directly to players.
 *
 * <p>Bukkit objects must only be used on the main thread, but video frames are produced on other threads. To
 * send packets from any thread safely, the network connection of every online player is cached in a concurrent
 * map when the player joins and removed when the player quits. Sending a packet then only touches the Netty
 * connection, which is thread-safe.
 *
 * <p>Joins are handled as early as possible, with {@link EventPriority#LOWEST}, so other plugins can send media in
 * their own join handlers, and quits as late as possible, with {@link EventPriority#MONITOR}, so they can still
 * send packets in their quit handlers.
 */
public final class PacketUtils {

  private static final Map<UUID, ServerGamePacketListenerImpl> PLAYER_CONNECTIONS = new ConcurrentHashMap<>();

  private static volatile @Nullable Listener LISTENER;

  private PacketUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Registers the connection tracking listener and caches the connections of every player that is already
   * online. Only meant to be called by {@link BukkitModule}.
   */
  public static synchronized void init() {
    shutdown();

    final Plugin plugin = BukkitModule.getPlugin();
    final Listener listener = new ConnectionListener();
    final PluginManager pluginManager = Bukkit.getPluginManager();
    final EventExecutor joinExecutor = (_, event) -> handleJoin(event);
    final EventExecutor quitExecutor = (_, event) -> handleQuit(event);
    pluginManager.registerEvent(PlayerJoinEvent.class, listener, EventPriority.LOWEST, joinExecutor, plugin);
    pluginManager.registerEvent(PlayerQuitEvent.class, listener, EventPriority.MONITOR, quitExecutor, plugin);
    LISTENER = listener;

    final Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
    for (final Player player : onlinePlayers) {
      addPlayerConnection(player);
    }
  }

  /**
   * Unregisters the connection tracking listener and clears all cached connections. Only meant to be called by
   * {@link BukkitModule}.
   */
  public static synchronized void shutdown() {
    final Listener listener = LISTENER;
    if (listener != null) {
      HandlerList.unregisterAll(listener);
      LISTENER = null;
    }
    PLAYER_CONNECTIONS.clear();
  }

  /**
   * Checks whether the player with the specified UUID is online and able to receive packets. May be called from
   * any thread.
   *
   * @param player the UUID of the player
   * @return true if packets can be sent to the player
   */
  public static boolean isConnected(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    return PLAYER_CONNECTIONS.containsKey(player);
  }

  /**
   * Sends the specified packets to every online player in the collection of viewers. Offline viewers are
   * skipped. May be called from any thread.
   *
   * @param viewers the UUIDs of the players to send the packets to
   * @param packets the packets to send, in order
   */
  public static void sendPackets(final Collection<UUID> viewers, final Packet<?>... packets) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    Preconditions.checkNotNull(packets, "Packets must not be null");
    for (final UUID viewer : viewers) {
      final ServerGamePacketListenerImpl connection = PLAYER_CONNECTIONS.get(viewer);
      if (connection == null) {
        continue;
      }
      for (final Packet<?> packet : packets) {
        connection.send(packet);
      }
    }
  }

  /**
   * Sends one packet to one player and calls the listener once its write completes or fails. May be called from any
   * thread.
   *
   * @param viewer   the UUID of the player
   * @param packet   the packet
   * @param listener called on the player's connection thread when the packet was written, or could not be
   * @return true if the player is online and the packet was handed to the connection, false if nothing was sent, in
   *     which case the listener is never called
   */
  public static boolean sendPacket(final UUID viewer, final Packet<?> packet, final ChannelFutureListener listener) {
    Preconditions.checkNotNull(viewer, "Viewer must not be null");
    Preconditions.checkNotNull(packet, "Packet must not be null");
    Preconditions.checkNotNull(listener, "Listener must not be null");
    final ServerGamePacketListenerImpl connection = PLAYER_CONNECTIONS.get(viewer);
    if (connection == null) {
      return false;
    }
    connection.send(packet, listener);
    return true;
  }

  /**
   * Caps how many bytes the operating system may hold unsent for a player (TCP_NOTSENT_LOWAT), on Linux with the
   * epoll transport Paper uses there. Above the cap the bytes wait in the connection's own queue, where
   * {@link #sendPacket(UUID, Packet, ChannelFutureListener)}'s listener sees how far writing got; without it the
   * system takes up to megabytes of a slow viewer's video at once and hides the backlog, and the player's game
   * packets wait behind all of it. Bytes already sent and waiting for their acknowledgement do not count, so the cap
   * does not slow a connection down. May be called from any thread.
   *
   * @param player the UUID of the player
   * @param bytes  the most unsent bytes the system may hold, positive
   * @return true if the cap was set; false for a player who is not online, a connection not open yet, or a transport
   *     without the option
   */
  public static boolean limitUnsent(final UUID player, final int bytes) {
    Preconditions.checkNotNull(player, "Player must not be null");
    Preconditions.checkArgument(bytes > 0, "Bytes must be positive");
    final ServerGamePacketListenerImpl listener = PLAYER_CONNECTIONS.get(player);
    // a game connection's network connection is only missing where there is no network, as in tests
    final Connection connection = listener == null ? null : listener.connection;
    final Channel channel = connection == null ? null : connection.channel;
    return channel != null && channel.config().setOption(EpollChannelOption.TCP_NOTSENT_LOWAT, (long) bytes);
  }

  private static void handleJoin(final Event event) {
    if (event instanceof final PlayerJoinEvent joinEvent) {
      final Player player = joinEvent.getPlayer();
      addPlayerConnection(player);
    }
  }

  private static void handleQuit(final Event event) {
    if (event instanceof final PlayerQuitEvent quitEvent) {
      final Player player = quitEvent.getPlayer();
      removePlayerConnection(player);
    }
  }

  private static void addPlayerConnection(final Player player) {
    final CraftPlayer craftPlayer = (CraftPlayer) player;
    final ServerPlayer handle = craftPlayer.getHandle();
    // online and joining players were placed into the world, which always assigns their connection first
    final ServerGamePacketListenerImpl connection = handle.connection;
    final UUID uuid = player.getUniqueId();
    PLAYER_CONNECTIONS.put(uuid, connection);
  }

  private static void removePlayerConnection(final Player player) {
    final UUID uuid = player.getUniqueId();
    PLAYER_CONNECTIONS.remove(uuid);
  }

  /**
   * The owner of the connection tracking handlers, which {@link #shutdown()} unregisters all at once.
   */
  private static final class ConnectionListener implements Listener {}
}
