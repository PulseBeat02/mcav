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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.epoll.EpollChannelOption;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.BukkitModule;
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
 *
 * @since 1.0.0
 */
public final class PacketUtils {

  private static final String PLAYER_NULL_MESSAGE = "Player must not be null!";

  private static final Map<UUID, CachedConnection> PLAYER_CONNECTIONS = new ConcurrentHashMap<>();

  private static volatile @Nullable Listener connectionListener;

  private PacketUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Registers the connection tracking listener and caches the connections of every player that is already
   * online. Only meant to be called by {@link BukkitModule}.
   *
   * <p>Run on the server main thread. Reinitialization unregisters the previous listener and rebuilds the cache.
   *
   * @throws IllegalStateException if no plugin has been injected
   * @since 1.0.0
   */
  public static synchronized void initialize() {
    final Map<UUID, CachedConnection> previous = Map.copyOf(PLAYER_CONNECTIONS);
    shutdown();

    final Plugin plugin = BukkitModule.getPlugin();
    final Listener listener = new ConnectionListener();
    final PluginManager pluginManager = Bukkit.getPluginManager();
    final EventExecutor joinExecutor = (_, event) -> onPlayerJoin(event);
    final EventExecutor quitExecutor = (_, event) -> onPlayerQuit(event);
    pluginManager.registerEvent(PlayerJoinEvent.class, listener, EventPriority.LOWEST, joinExecutor, plugin);
    pluginManager.registerEvent(PlayerQuitEvent.class, listener, EventPriority.MONITOR, quitExecutor, plugin);
    connectionListener = listener;

    final Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
    for (final Player player : onlinePlayers) {
      addPlayerConnection(player, previous.get(player.getUniqueId()));
    }
  }

  /**
   * Unregisters the connection tracking listener and clears all cached connections. Only meant to be called by
   * {@link BukkitModule}.
   *
   * <p>Run on the server main thread. Existing player connections remain server-owned and are not closed.
   *
   * @since 1.0.0
   */
  public static synchronized void shutdown() {
    final Listener listener = connectionListener;
    if (listener != null) {
      HandlerList.unregisterAll(listener);
      connectionListener = null;
    }

    PLAYER_CONNECTIONS.clear();
  }

  /**
   * Returns whether the player with the specified UUID is online and able to receive packets. May be called from any
   * thread.
   *
   * @param player the UUID of the player
   * @return true if a connection is currently cached; this is a snapshot and does not guarantee that a
   *         subsequent send succeeds or that the client receives it
   * @throws NullPointerException if {@code player} is null
   * @since 1.0.0
   */
  public static boolean isConnected(final UUID player) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);

    return PLAYER_CONNECTIONS.containsKey(player);
  }

  /**
   * Returns an opaque identity for the player's current connection. May be called from any thread. The identity is
   * stable while that connection is cached, including cache refreshes, and changes when the player reconnects.
   * Retaining it does not retain the player or the network connection.
   *
   * @param player the player's UUID
   * @return the connection identity, or empty while the player is offline; a snapshot, not a guarantee of delivery
   * @throws NullPointerException if {@code player} is null
   * @since 1.0.0
   */
  public static Optional<Object> findConnectionIdentity(final UUID player) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);

    final @Nullable CachedConnection cached = PLAYER_CONNECTIONS.get(player);
    return Optional.ofNullable(cached).map(CachedConnection::identity);
  }

  /**
   * Sends the specified packets to every online player in the collection of viewers. Offline viewers are
   * skipped. May be called from any thread.
   *
   * @param viewers the UUIDs of the players to send the packets to
   * @param packets the packets to send, in order
   * @throws NullPointerException if {@code viewers} or {@code packets} is null
   * @since 1.0.0
   */
  public static void sendPackets(final Collection<UUID> viewers, final Packet<?>... packets) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");
    Preconditions.checkNotNull(packets, "Packets must not be null!");

    for (final UUID viewer : viewers) {
      final CachedConnection cached = PLAYER_CONNECTIONS.get(viewer);
      if (cached == null) {
        continue;
      }
      for (final Packet<?> packet : packets) {
        cached.listener().send(packet);
      }
    }
  }

  /**
   * Sends one packet to one player at once and calls the listener once its write completes or fails. May be called
   * from any thread.
   *
   * <p>The packet is written to the player's connection and flushed right away. Paper holds most packets that a plugin
   * sends from another thread, map packets among them, until the server's next tick, which would deliver the frames of
   * a video faster than 20 a second two at a time; a packet sent here does not wait for packets that Paper holds that
   * way.
   *
   * @param viewer   the UUID of the player
   * @param packet   the packet
   * @param listener the listener called on the player's connection thread once the write succeeds or fails
   * @return true if the packet was handed to the player's connection, false for a player who is not online or a
   *         connection not open yet, in which case the listener is never called
   * @throws NullPointerException if {@code viewer}, {@code packet} or {@code listener} is null
   * @since 1.0.0
   */
  public static boolean sendPacket(final UUID viewer, final Packet<?> packet, final ChannelFutureListener listener) {
    Preconditions.checkNotNull(viewer, "Viewer must not be null!");
    Preconditions.checkNotNull(packet, "Packet must not be null!");
    Preconditions.checkNotNull(listener, "Listener must not be null!");

    final Channel channel = findChannel(viewer);
    if (channel == null) {
      return false;
    }

    final ChannelFuture written = channel.writeAndFlush(packet);
    written.addListener(listener);

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
   * <p>The transport owns the option and may reject it with a transport-specific runtime exception. A true
   * return reports option acceptance, not a measurement of the connection backlog.
   *
   * @param player the UUID of the player
   * @param bytes  the most unsent bytes the system may hold, positive
   * @return true if the cap was set; false for a player who is not online, a connection not open yet, or a transport
   *         without the option
   * @throws IllegalArgumentException if the byte limit is nonpositive
   * @throws NullPointerException     if {@code player} is null
   * @since 1.0.0
   */
  public static boolean limitUnsent(final UUID player, final int bytes) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);
    Preconditions.checkArgument(bytes > 0, "Bytes must be positive!");

    final Channel channel = findChannel(player);
    return channel != null && channel.config().setOption(EpollChannelOption.TCP_NOTSENT_LOWAT, (long) bytes);
  }

  private static @Nullable Channel findChannel(final UUID player) {
    final CachedConnection cached = PLAYER_CONNECTIONS.get(player);
    return cached == null ? null : cached.listener().connection.channel;
  }

  private static void onPlayerJoin(final Event event) {
    if (event instanceof final PlayerJoinEvent joinEvent) {
      final Player player = joinEvent.getPlayer();
      addPlayerConnection(player, PLAYER_CONNECTIONS.get(player.getUniqueId()));
    }
  }

  private static void onPlayerQuit(final Event event) {
    if (event instanceof final PlayerQuitEvent quitEvent) {
      final Player player = quitEvent.getPlayer();
      removePlayerConnection(player);
    }
  }

  private static void addPlayerConnection(final Player player, final @Nullable CachedConnection previous) {
    final CraftPlayer craftPlayer = (CraftPlayer) player;
    final ServerPlayer handle = craftPlayer.getHandle();
    final ServerGamePacketListenerImpl listener = handle.connection;
    final UUID uuid = player.getUniqueId();
    final Object identity = previous != null && listener.equals(previous.listener()) ? previous.identity() : new Object();
    PLAYER_CONNECTIONS.put(uuid, new CachedConnection(listener, identity));
  }

  private static void removePlayerConnection(final Player player) {
    final UUID uuid = player.getUniqueId();
    PLAYER_CONNECTIONS.remove(uuid);
  }

  private static final class ConnectionListener implements Listener {}

  private record CachedConnection(ServerGamePacketListenerImpl listener, Object identity) {}
}
