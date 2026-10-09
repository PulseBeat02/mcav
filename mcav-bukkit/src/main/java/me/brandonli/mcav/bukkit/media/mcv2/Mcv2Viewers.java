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
import java.util.concurrent.atomic.AtomicLong;
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
 * dithered maps, so no one is shown a screen their client cannot decode. That includes a player whose MCV2 client mod
 * reports, on the {@code mcav:mcv2} plugin channel, that Iris draws a shader pack under which MCV2 does not decode:
 * they keep the dithered maps until it reports otherwise.
 *
 * <p>The states may be read from any thread; the refusal callback runs on the main thread, where the events are
 * fired. Whoever shows the video reads {@link #isLoaded(UUID)}, which is how {@link Mcv2Result} learns of new players
 * with the pack.
 *
 * <p>This tracker records client status reports; without the client mod it cannot verify shader compatibility, and it
 * never verifies successful frame decoding. Register/unregister listeners and retarget the pack on the main thread.
 * Reads may occur from media threads. Registration alone does not offer a pack; the caller sends the request and
 * records it with {@link #requested(UUID)}. Unregistering retains recorded state.
 */
public final class Mcv2Viewers {

  /** What a player's client reported for the pack. */
  public enum PackState {
    /** The pack was requested and the client has not finished loading it. */
    REQUESTED,
    /** The client loaded the pack. */
    LOADED,
    /** The client could not download or apply the pack. */
    REFUSED,
    /** The player declined the pack, and is not asked again while online, whichever pack follows. */
    DECLINED,
  }

  private volatile UUID packId;

  private final Consumer<Player> onRefused;

  private final Map<UUID, PackState> states;
  // A client reload after reconnecting or another pack requires a new session.
  private final Map<UUID, Long> sessions;
  private final AtomicLong nextSession;

  private final Mcv2ShaderReports shaderReports;

  private @Nullable Listener listener;

  /**
   * Constructs a new tracker.
   *
   * @param packId    the id of the pack request, as sent to the players
   * @param onRefused called with a player whose client just refused the pack or failed to load it, for example to
   *                  tell them why they see the dithered maps
   * @throws NullPointerException if {@code packId} or {@code onRefused} is null
   */
  public Mcv2Viewers(final UUID packId, final Consumer<Player> onRefused) {
    Preconditions.checkNotNull(packId, "Pack id must not be null");
    Preconditions.checkNotNull(onRefused, "Refused callback must not be null");
    this.packId = packId;
    this.onRefused = onRefused;
    this.states = new ConcurrentHashMap<>();
    this.sessions = new ConcurrentHashMap<>();
    this.nextSession = new AtomicLong();
    this.shaderReports = new Mcv2ShaderReports(System::nanoTime);
  }

  /**
   * Starts listening to the players' pack status, to the reports of their MCV2 client mod, and to players leaving, who
   * lose their state. Register before players join: a client learns of the plugin channel as it joins, and its mod
   * reports only once it knows the channel.
   * @throws IllegalStateException if no plugin has been injected
   */
  public synchronized void register() {
    this.unregister();
    final Listener events = new Listener() {};
    final PluginManager manager = Bukkit.getPluginManager();
    final EventExecutor status = (_, event) -> this.handleStatus((PlayerResourcePackStatusEvent) event);
    final EventExecutor quit = (_, event) -> this.handleQuit((PlayerQuitEvent) event);
    manager.registerEvent(PlayerResourcePackStatusEvent.class, events, EventPriority.MONITOR, status, BukkitModule.getPlugin());
    manager.registerEvent(PlayerQuitEvent.class, events, EventPriority.MONITOR, quit, BukkitModule.getPlugin());
    Bukkit.getMessenger().registerIncomingPluginChannel(BukkitModule.getPlugin(), Mcv2ShaderReports.CHANNEL, this.shaderReports);
    this.listener = events;
  }

  /**
   * Stops listening.
   */
  public synchronized void unregister() {
    final Listener events = this.listener;
    if (events != null) {
      HandlerList.unregisterAll(events);
      Bukkit.getMessenger().unregisterIncomingPluginChannel(BukkitModule.getPlugin(), Mcv2ShaderReports.CHANNEL, this.shaderReports);
      this.listener = null;
    }
  }

  /**
   * Follows another pack from now on, which replaces the one followed so far: nobody has it loaded until their client
   * reports so, and what they reported for the old pack no longer counts, except that a player who declined stays
   * declined.
   *
   * @param newPackId the id of the new pack's request
   * @throws NullPointerException if {@code newPackId} is null
   */
  public void retarget(final UUID newPackId) {
    Preconditions.checkNotNull(newPackId, "Pack id must not be null");
    this.packId = newPackId;
    this.states.values().removeIf(state -> state != PackState.DECLINED);
    this.sessions.clear();
  }

  /**
   * Gets the id of the pack followed.
   *
   * @return the id of the pack's request
   */
  public UUID getPackId() {
    return this.packId;
  }

  /**
   * Records that the pack was requested from a player.
   *
   * @param player the player's UUID
   * @throws NullPointerException if {@code player} is null
   */
  public void requested(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    this.states.put(player, PackState.REQUESTED);
  }

  /**
   * Gets what a player's client reported.
   *
   * @param player the player's UUID
   * @return the recorded state, or null when no state is currently tracked, including after quit or retarget
   * @throws NullPointerException if {@code player} is null
   */
  public @Nullable PackState getState(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    return this.states.get(player);
  }

  /**
   * Checks whether a player's client loaded the pack and can decode with it: one whose MCV2 client mod reports a shader
   * pack in use, under which MCV2 does not decode, cannot, whatever its pack status.
   *
   * @param player the player's UUID
   * @return true if the tracked client status is LOADED and no report of the client mod rules decoding out; this is not
   *         a playback acknowledgment
   */
  public boolean isLoaded(final UUID player) {
    return this.getState(player) == PackState.LOADED && !this.shaderReports.blocksDecoding(player);
  }

  boolean hasShaderReport(final UUID player) {
    return this.shaderReports.hasReported(player);
  }

  /**
   * Gets the session of a player's client with the pack: a number that changes whenever the client loads the pack
   * anew, after its player joined again or after another pack. A screen shown to the client of an earlier session is
   * not shown to the client of this one.
   *
   * @param player the player's UUID
   * @return the session, or 0 while the client has not loaded the pack
   * @throws NullPointerException if {@code player} is null
   */
  public long getSession(final UUID player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    return this.sessions.getOrDefault(player, 0L);
  }

  void handleStatus(final PlayerResourcePackStatusEvent event) {
    if (!this.packId.equals(event.getID())) {
      return;
    }
    final Player player = event.getPlayer();
    final UUID viewerId = player.getUniqueId();
    switch (event.getStatus()) {
      case SUCCESSFULLY_LOADED -> this.loaded(viewerId);
      case ACCEPTED, DOWNLOADED -> this.states.put(viewerId, PackState.REQUESTED);
      case DECLINED -> this.refuse(player, PackState.DECLINED);
      default -> this.refuse(player, PackState.REFUSED);
    }
  }

  private void loaded(final UUID player) {
    final PackState before = this.states.put(player, PackState.LOADED);
    if (before != PackState.LOADED) {
      this.sessions.put(player, this.nextSession.incrementAndGet());
    }
  }

  private void refuse(final Player player, final PackState state) {
    this.sessions.remove(player.getUniqueId());
    final PackState before = this.states.put(player.getUniqueId(), state);
    if (before != PackState.REFUSED && before != PackState.DECLINED) {
      this.onRefused.accept(player);
    }
  }

  void handleQuit(final PlayerQuitEvent event) {
    final UUID player = event.getPlayer().getUniqueId();
    this.states.remove(player);
    this.sessions.remove(player);
    this.shaderReports.forget(player);
  }
}
