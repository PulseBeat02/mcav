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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.media.mcv2.transport.TransportPages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
 */
public final class Mcv2Channel {

  private final Mcv2Configuration configuration;
  private final Mcv2Viewers viewers;
  private final Mcv2Screen screen;
  private final Set<UUID> scheduled;
  /** The viewers shown the screen, each with its link. */
  private final Map<UUID, Mcv2Link> links;
  /** The viewers that receive frames as of the last update, with their links. */
  private volatile Map<UUID, Mcv2Link> recipients;
  private volatile boolean keyframeRequested;

  /**
   * Constructs a new channel.
   *
   * @param configuration the screen
   * @param viewers       who has the pack loaded
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
   * Spawns the screen's page frames. Call on the main thread.
   */
  public void open() {
    this.screen.build();
  }

  /**
   * Removes the screen's page frames. Call on the main thread.
   */
  public void close() {
    this.screen.remove();
    this.scheduled.clear();
    this.links.clear();
    this.recipients = Map.of();
  }

  /**
   * Sorts the configured viewers, and schedules showing the screen to those whose pack just loaded.
   *
   * @return the viewers who do not receive frames and should be shown the dithered maps
   */
  public Set<UUID> update() {
    final Map<UUID, Mcv2Link> receiving = new HashMap<>();
    final Set<UUID> others = ConcurrentHashMap.newKeySet();
    for (final UUID viewer : this.configuration.getViewers()) {
      final Mcv2Link link = this.links.get(viewer);
      if (!this.viewers.isLoaded(viewer)) {
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

  /** Shows the screen to a viewer whose pack loaded, then lets the next frame, a keyframe, reach the viewer. */
  void show(final UUID viewer) {
    final Player player = Bukkit.getPlayer(viewer);
    if (player == null || !this.scheduled.contains(viewer)) {
      this.scheduled.remove(viewer);
      return;
    }
    this.screen.show(player);
    // a viewer shown the screen again starts over: its client holds no picture of this stream yet
    this.links.put(viewer, new Mcv2Link(this.configuration.getBacklogLimit()));
    this.keyframeRequested = true;
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
    this.keyframeRequested = true;
  }

  /**
   * Takes a keyframe request: a viewer was just shown the screen, or a frame could not be sent.
   *
   * @return true if the next frame should be a keyframe
   */
  public boolean takeKeyframeRequest() {
    final boolean requested = this.keyframeRequested;
    this.keyframeRequested = false;
    return requested;
  }

  /**
   * Sends one frame to the viewers that were shown the screen and can take it, as one bundle: its pages on the page
   * maps, and with a keyframe the anchors, which the server's own map data may have overwritten. A viewer gets the
   * frame when its link admits it: the viewer can decode it and its connection is not over the backlog limit.
   *
   * @param frame the frame
   * @return the map colours of the frame, or -1 if the frame has more pages than the screen has page slots, in which
   *     case nothing is sent and the next frame is asked to be a keyframe
   * @throws IllegalArgumentException if the bytes are not a valid MCV2 frame
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
    if (pages.size() > this.configuration.getPageSlots()) {
      this.keyframeRequested = true;
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
    final boolean keyframe = header.isKeyframe();
    if (keyframe) {
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
      if (link.offer(header.getFrameId(), header.getReferenceId(), keyframe, bytes)) {
        // the bundle's bytes leave the backlog once written, or at once for a viewer who left
        for (final MapPacketFactory.Bundle bundle : bundles) {
          bundle.send(viewer, () -> link.written(bundle.bytes()));
        }
      }
    }
    return colors;
  }
}
