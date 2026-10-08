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
package me.brandonli.mcav.sandbox.listener;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * The players online, kept as they join and leave. A screen shown to {@code @a} watches through this set, so a player
 * who joins while it plays is one of its viewers; the set may be read from any thread, unlike the server's own list.
 */
public final class OnlinePlayers implements Listener {

  private final Set<UUID> online;

  private final Set<UUID> view;

  /**
   * Constructs an empty set; {@link #start(Plugin)} fills it.
   */
  public OnlinePlayers() {
    this.online = ConcurrentHashMap.newKeySet();
    this.view = Collections.unmodifiableSet(this.online);
  }

  /**
   * Starts following the players, from those online now. Call on the main thread.
   *
   * @param plugin the plugin that listens
   */
  public void start(final Plugin plugin) {
    for (final Player player : Bukkit.getOnlinePlayers()) {
      this.online.add(player.getUniqueId());
    }
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  /**
   * Stops following the players.
   */
  public void stop() {
    HandlerList.unregisterAll(this);
    this.online.clear();
  }

  /**
   * Counts a player who joins before any other listener hears of it.
   *
   * @param event the join
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onJoin(final PlayerJoinEvent event) {
    this.online.add(event.getPlayer().getUniqueId());
  }

  /**
   * Forgets a player who leaves once every other listener heard of it.
   *
   * @param event the quit
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(final PlayerQuitEvent event) {
    this.online.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Gets the players online.
   *
   * @return their UUIDs, a view that follows joins and quits
   */
  public Set<UUID> getPlayers() {
    return this.view;
  }
}
