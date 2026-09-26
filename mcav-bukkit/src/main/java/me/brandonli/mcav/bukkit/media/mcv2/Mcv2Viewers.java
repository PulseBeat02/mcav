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
package me.brandonli.mcav.bukkit.media.mcv2;

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.BukkitModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tracks which players have the MCV2 resource pack loaded, from the status their client reports for the pack's
 * request id. Only a player whose client reported the pack as loaded sees the decoded video; everyone else keeps the
 * dithered maps, so no one is shown a screen their client cannot decode.
 *
 * <p>The states may be read from any thread; the refusal callback runs on the main thread, where the events are
 * fired. Whoever shows the video reads {@link #isLoaded(UUID)}, which is how {@link Mcv2Result} learns of new players
 * with the pack.
 */
public final class Mcv2Viewers {

  /** What a player's client reported for the pack. */
  public enum PackState {
    /** The pack was requested and the client has not finished loading it. */
    REQUESTED,
    /** The client loaded the pack. */
    LOADED,
    /** The client declined the pack, or could not download or apply it. */
    REFUSED,
  }

  private final UUID packId;

  private final Consumer<Player> onRefused;

  private final Map<UUID, PackState> states;

  private @Nullable Listener listener;

  /**
   * Constructs a new tracker.
   *
   * @param packId    the id of the pack request, as sent to the players
   * @param onRefused called with a player whose client just refused the pack or failed to load it, for example to
   *                  tell them why they see the dithered maps
   */
  public Mcv2Viewers(final UUID packId, final Consumer<Player> onRefused) {
    Preconditions.checkNotNull(packId, "Pack id must not be null");
    Preconditions.checkNotNull(onRefused, "Refused callback must not be null");
    this.packId = packId;
    this.onRefused = onRefused;
    this.states = new ConcurrentHashMap<>();
  }

  /**
   * Starts listening to the players' pack status and to players leaving, who lose their state.
   */
  public synchronized void register() {
    this.unregister();
    final Listener events = new Listener() {};
    final PluginManager manager = Bukkit.getPluginManager();
    final EventExecutor status = (_, event) -> this.handleStatus((PlayerResourcePackStatusEvent) event);
    final EventExecutor quit = (_, event) -> this.handleQuit((PlayerQuitEvent) event);
    manager.registerEvent(PlayerResourcePackStatusEvent.class, events, EventPriority.MONITOR, status, BukkitModule.getPlugin());
    manager.registerEvent(PlayerQuitEvent.class, events, EventPriority.MONITOR, quit, BukkitModule.getPlugin());
    this.listener = events;
  }

  /**
   * Stops listening.
   */
  public synchronized void unregister() {
    final Listener events = this.listener;
    if (events != null) {
      HandlerList.unregisterAll(events);
      this.listener = null;
    }
  }

  /**
   * Records that the pack was requested from a player.
   *
   * @param player the player's UUID
   */
  public void requested(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    this.states.put(player, PackState.REQUESTED);
  }

  /**
   * Gets what a player's client reported.
   *
   * @param player the player's UUID
   * @return the state, or null if the pack was never requested from the player or they left
   */
  public @Nullable PackState getState(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    return this.states.get(player);
  }

  /**
   * Checks whether a player's client loaded the pack.
   *
   * @param player the player's UUID
   * @return true if the player sees the decoded video
   */
  public boolean isLoaded(final UUID player) {
    return this.getState(player) == PackState.LOADED;
  }

  void handleStatus(final PlayerResourcePackStatusEvent event) {
    if (!this.packId.equals(event.getID())) {
      return;
    }
    final Player player = event.getPlayer();
    final UUID uuid = player.getUniqueId();
    switch (event.getStatus()) {
      case SUCCESSFULLY_LOADED -> this.states.put(uuid, PackState.LOADED);
      case ACCEPTED, DOWNLOADED -> this.states.put(uuid, PackState.REQUESTED);
      default -> {
        if (this.states.put(uuid, PackState.REFUSED) != PackState.REFUSED) {
          this.onRefused.accept(player);
        }
      }
    }
  }

  void handleQuit(final PlayerQuitEvent event) {
    this.states.remove(event.getPlayer().getUniqueId());
  }
}
