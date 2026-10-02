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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Delivers encoded MCV2 frames to the viewers of a screen who can decode them.
 *
 * <p>{@link #update()} sorts the viewers by what their client reported for the pack: a viewer whose pack loaded is
 * first shown the screen on the main thread, and only after that receives frames, starting with a keyframe, which
 * the viewer needs to start; every other viewer is returned, to be shown the dithered maps instead.
 * {@link #send(byte[])} sends one frame's pages on the page maps, and with a keyframe the anchors too, to the viewers
 * that were shown the screen, each through its own {@link Mcv2Link}: a viewer whose connection falls behind misses
 * frames until one it can decode comes with its backlog under the configuration's limit, and the others are not held
 * back. The two may be called from any thread, but not concurrently with each other.
 *
 * <p>The channel owns its hidden page-frame screen and per-viewer links, but does not register or unregister
 * the caller-owned pack tracker. Open and close on the main thread; stop frame delivery before closing. Serialize
 * update, send and keyframe-request consumption. A successful send records a delivery attempt, not a client decode
 * acknowledgment, and can succeed when no viewers are currently eligible.
 *
 * <p>While the channel is open, a viewer farther from the wall than their view distance, or in another world, receives
 * nothing: no frames, and no dithered maps either, as their client cannot see the wall. Coming back, the viewer is
 * shown the screen again and starts on a keyframe. The distances are measured on the main thread once a second; a
 * viewer who is near goes out of range only {@value #RANGE_MARGIN} blocks farther, so one at the edge does not come
 * and go.
 */
public final class Mcv2Channel {

  /** The blocks past their view distance a viewer near the wall may go before the screen stops sending to them. */
  static final int RANGE_MARGIN = 32;

  private static final int CHUNK_BLOCKS = 16;

  /** How often the distances are measured: once a second. */
  private static final long RANGE_TICKS = 20;

  private final Mcv2Configuration configuration;

  private final Mcv2Viewers viewers;

  private final Mcv2Screen screen;

  private final Set<UUID> scheduled;

  /** The viewers shown the screen, each with its link. */
  private final Map<UUID, Mcv2Link> links;

  /** The viewers that receive frames as of the last update, with their links. */
  private volatile Map<UUID, Mcv2Link> recipients;

  private final Mcv2KeyframeRequest keyframeRequest;

  /** The viewers too far from the wall to see it, as of the last measurement. */
  private volatile Set<UUID> farAway;

  private @Nullable BukkitTask ranging;

  /**
   * Constructs a new channel.
   *
   * @param configuration the screen
   * @param viewers       who has the pack loaded
   * @throws NullPointerException if configuration or viewers is null
   */
  public Mcv2Channel(final Mcv2Configuration configuration, final Mcv2Viewers viewers) {
    this(configuration, viewers, new Mcv2Screen(configuration));
  }

  Mcv2Channel(final Mcv2Configuration configuration, final Mcv2Viewers viewers, final Mcv2Screen screen) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(viewers, "Viewers must not be null");
    Preconditions.checkNotNull(screen, "Screen must not be null");
    this.configuration = configuration;
    this.viewers = viewers;
    this.screen = screen;
    this.scheduled = ConcurrentHashMap.newKeySet();
    this.links = new ConcurrentHashMap<>();
    this.recipients = Map.of();
    this.farAway = Set.of();
    this.keyframeRequest = new Mcv2KeyframeRequest();
  }

  /**
   * Gets the screen.
   *
   * @return the screen
   */
  public Mcv2Screen getScreen() {
    return this.screen;
  }

  /**
   * Gets the configuration of the screen the channel sends to: its video size, stream id and viewers.
   *
   * @return the configuration
   */
  public Mcv2Configuration getConfiguration() {
    return this.configuration;
  }

  /**
   * Spawns the screen's page frames, and starts measuring how far the viewers are from the wall. Call on the main
   * thread.
   * @throws IllegalStateException if the screen is already built or no plugin has been injected
   * @throws NullPointerException if the origin no longer resolves to a world
   */
  public void open() {
    this.screen.build();
    this.measureRange();
    this.ranging = Bukkit.getScheduler().runTaskTimer(BukkitModule.getPlugin(), this::measureRange, RANGE_TICKS, RANGE_TICKS);
  }

  /**
   * Removes the screen's page frames, and stops measuring the viewers' distances. Call on the main thread.
   */
  public void close() {
    final BukkitTask task = this.ranging;
    if (task != null) {
      task.cancel();
      this.ranging = null;
    }
    this.screen.remove();
    this.scheduled.clear();
    this.links.clear();
    this.recipients = Map.of();
    this.farAway = Set.of();
  }

  /** Finds the viewers too far from the wall to see it, on the main thread. */
  void measureRange() {
    final Set<UUID> before = this.farAway;
    final Set<UUID> far = new HashSet<>();
    for (final UUID viewer : this.configuration.getViewers()) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player != null && this.isFarAway(player, before.contains(viewer))) {
        far.add(viewer);
      }
    }
    this.farAway = Set.copyOf(far);
  }

  /**
   * Whether a player cannot see the wall: in another world, or farther from it than their view distance; one who could
   * see it goes out of range only {@value #RANGE_MARGIN} blocks farther. A player whose view distance is not known is
   * never far away.
   */
  private boolean isFarAway(final Player player, final boolean wasFarAway) {
    final int viewDistance = player.getViewDistance();
    if (viewDistance <= 0) {
      return false;
    }
    final World wall = this.configuration.getOrigin().getWorld();
    if (wall == null || !wall.getUID().equals(player.getWorld().getUID())) {
      return true;
    }
    final double reach = viewDistance * CHUNK_BLOCKS + (wasFarAway ? 0 : RANGE_MARGIN);
    return this.distanceToWall(player.getLocation()) > reach;
  }

  /** The distance from a point to the nearest block of the wall, in blocks. */
  private double distanceToWall(final Location location) {
    final Location origin = this.configuration.getOrigin();
    final BlockFace right = this.configuration.getRight();
    final int span = this.configuration.getColumns() - 1;
    final int firstX = origin.getBlockX();
    final int lastX = firstX + right.getModX() * span;
    final int firstZ = origin.getBlockZ();
    final int lastZ = firstZ + right.getModZ() * span;
    final double outsideX = outside(location.getX(), Math.min(firstX, lastX), Math.max(firstX, lastX) + 1);
    final double outsideY = outside(location.getY(), origin.getBlockY() - (this.configuration.getRows() - 1), origin.getBlockY() + 1);
    final double outsideZ = outside(location.getZ(), Math.min(firstZ, lastZ), Math.max(firstZ, lastZ) + 1);
    return Math.sqrt(outsideX * outsideX + outsideY * outsideY + outsideZ * outsideZ);
  }

  /** How far a coordinate lies outside a range, 0 inside it. */
  private static double outside(final double coordinate, final double low, final double high) {
    return Math.max(0, Math.max(low - coordinate, coordinate - high));
  }

  /**
   * Sorts the configured viewers, and schedules showing the screen to those whose pack just loaded. A viewer too far
   * from the wall to see it is in neither group. A viewer no longer configured is shown the screen no more: their link
   * is retired, and the page frames are hidden from them on the main thread.
   *
   * @return the viewers who do not receive frames and should be shown the dithered maps
   * @throws IllegalStateException if showing a new viewer requires scheduling before a plugin has been injected
   */
  public Set<UUID> update() {
    // one view of a collection the caller may change while this runs
    final Set<UUID> selected = new HashSet<>(this.configuration.getViewers());
    this.retireRemoved(selected);
    final Map<UUID, Mcv2Link> receiving = new HashMap<>();
    final Set<UUID> others = ConcurrentHashMap.newKeySet();
    final Set<UUID> far = this.farAway;
    for (final UUID viewer : selected) {
      final Mcv2Link link = this.links.get(viewer);
      if (far.contains(viewer)) {
        // the client cannot see the wall: nothing is sent, and coming back the viewer is shown the screen anew
        this.scheduled.remove(viewer);
        this.links.remove(viewer);
      } else if (!this.viewers.isLoaded(viewer)) {
        others.add(viewer);
        this.scheduled.remove(viewer);
        this.links.remove(viewer);
      } else if (link != null) {
        receiving.put(viewer, link);
      } else {
        others.add(viewer);
        if (this.scheduled.add(viewer)) {
          Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.show(viewer));
        }
      }
    }
    this.recipients = Collections.unmodifiableMap(receiving);
    return others;
  }

  /**
   * Retires the viewers removed from the configuration: a viewer the screen was still to be shown to is not shown it,
   * and one shown it loses their link, and has the page frames hidden on the main thread. The removed viewer kept the
   * screen otherwise, frozen on its last frame, and the channel kept their link.
   */
  private void retireRemoved(final Set<UUID> selected) {
    this.scheduled.removeIf(viewer -> !selected.contains(viewer));
    for (final UUID viewer : Set.copyOf(this.links.keySet())) {
      if (!selected.contains(viewer)) {
        this.links.remove(viewer);
        Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.hide(viewer));
      }
    }
  }

  /** Hides the screen from a viewer removed from the configuration, unless they were shown it again since. */
  private void hide(final UUID viewer) {
    if (this.scheduled.contains(viewer) || this.links.containsKey(viewer)) {
      return;
    }
    final Player player = Bukkit.getPlayer(viewer);
    if (player != null) {
      this.screen.hide(player);
    }
  }

  /** Shows the screen to a viewer whose pack loaded, then lets the next frame, a keyframe, reach the viewer. */
  void show(final UUID viewer) {
    final Player player = Bukkit.getPlayer(viewer);
    // a viewer removed from the configuration since the show was scheduled is not shown the screen
    if (player == null || !this.scheduled.contains(viewer) || !this.configuration.getViewers().contains(viewer)) {
      this.scheduled.remove(viewer);
      return;
    }
    this.screen.show(player);
    // the rest of the viewer's video waits where its backlog limit sees it
    final int unsent = this.configuration.getUnsentLimit();
    if (unsent > 0) {
      PacketUtils.limitUnsent(viewer, unsent);
    }
    // a viewer shown the screen again starts over: its client holds no picture of this stream yet
    this.links.put(viewer, new Mcv2Link(this.configuration.getBacklogLimit()));
    this.keyframeRequest.request();
  }

  /**
   * Gets the viewers that receive frames.
   *
   * @return the viewers shown the screen, as of the last {@link #update()}
   */
  public Set<UUID> getRecipients() {
    return Set.copyOf(this.recipients.keySet());
  }

  /**
   * Gets the links of the viewers shown the screen: what each was sent, missed and has not yet written.
   *
   * @return the links by viewer, a live view
   */
  public Map<UUID, Mcv2Link> getLinks() {
    return Collections.unmodifiableMap(this.links);
  }

  /**
   * Asks for the next frame to be a keyframe.
   */
  public void requestKeyframe() {
    this.keyframeRequest.request();
  }

  /**
   * Takes a keyframe request: a viewer was just shown the screen, or a frame could not be sent.
   *
   * @return true if the next frame should be a keyframe
   */
  public boolean takeKeyframeRequest() {
    return this.keyframeRequest.take();
  }

  /**
   * Sends one frame to the viewers that were shown the screen and can take it, as one bundle: its pages on the page
   * maps, and with a keyframe the anchors, which the server's own map data may have overwritten. A viewer gets the
   * frame when its link admits it: the viewer can decode it and its connection is not over the backlog limit.
   *
   * @param frame the frame
   * @return the number of page-map color bytes per potential recipient, excluding anchors and packet overhead, or -1 if the frame has more pages than the screen has page slots, in which
   *     case nothing is sent and the next frame is asked to be a keyframe
   * @throws IllegalArgumentException if the bytes are not a valid MCV2 frame
   * @throws NullPointerException if {@code frame} is null
   */
  public int send(final byte[] frame) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    final Mcv2Frame header;
    final List<byte[]> pages;
    try {
      header = FrameParser.parse(frame);
      pages = TransportPages.makePages(frame, this.configuration.getStreamId(), MapAlphabet.SYMBOL_BITS);
    } catch (final Mcv2Exception exception) {
      throw new IllegalArgumentException("Not a valid MCV2 frame: " + exception.getMessage(), exception);
    }
    final Mcv2SendEvent event = new Mcv2SendEvent();
    event.frameId = header.getFrameId();
    event.referenceId = header.getReferenceId();
    event.keyframe = header.isKeyframe();
    event.bytes = frame.length;
    if (pages.size() > this.configuration.getPageSlots()) {
      this.keyframeRequest.request();
      event.colors = -1;
      event.commit();
      return -1;
    }
    final List<MapTilePatch> patches = new ArrayList<>();
    int colors = 0;
    for (int page = 0; page < pages.size(); page++) {
      final byte[] mapColors = MapAlphabet.toMapColors(pages.get(page));
      colors += mapColors.length;
      final int rows = mapColors.length / MapLayout.MAP_SIZE;
      patches.add(new MapTilePatch(this.configuration.getPageMap() + page, 0, 0, MapLayout.MAP_SIZE, rows, mapColors));
    }
    final boolean isKeyframe = header.isKeyframe();
    if (isKeyframe) {
      patches.addAll(this.screen.anchors());
    }
    final List<MapPacketFactory.Bundle> bundles = MapPacketFactory.bundles(patches);
    long bytes = 0;
    for (final MapPacketFactory.Bundle bundle : bundles) {
      bytes += bundle.bytes();
    }
    for (final Map.Entry<UUID, Mcv2Link> recipient : this.recipients.entrySet()) {
      final UUID viewer = recipient.getKey();
      final Mcv2Link link = recipient.getValue();
      final long behind = link.getBehind();
      if (link.offer(header.getFrameId(), header.getReferenceId(), isKeyframe, bytes)) {
        event.sentTo++;
        // the bundle's bytes leave the backlog once written, or at once for a viewer who left
        for (final MapPacketFactory.Bundle bundle : bundles) {
          bundle.send(viewer, () -> link.written(bundle.bytes()));
        }
      } else if (link.getBehind() > behind) {
        event.behind++;
      } else {
        event.waiting++;
      }
      event.backlog = Math.max(event.backlog, link.getBacklog());
    }
    event.colors = colors;
    event.sent = System.currentTimeMillis();
    event.commit();
    return colors;
  }
}
