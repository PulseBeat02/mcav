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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
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
 * that were shown the screen. The two may be called from any thread, but not concurrently with each other.
 */
public final class Mcv2Channel {

  private final Mcv2Configuration configuration;
  private final Mcv2Viewers viewers;
  private final Mcv2Screen screen;
  private final Set<UUID> scheduled;
  private final Set<UUID> shown;
  private volatile Set<UUID> recipients;
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
    this.shown = ConcurrentHashMap.newKeySet();
    this.recipients = Set.of();
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
    this.shown.clear();
    this.recipients = Set.of();
  }

  /**
   * Sorts the configured viewers, and schedules showing the screen to those whose pack just loaded.
   *
   * @return the viewers who do not receive frames and should be shown the dithered maps
   */
  public Set<UUID> update() {
    final Set<UUID> receiving = ConcurrentHashMap.newKeySet();
    final Set<UUID> others = ConcurrentHashMap.newKeySet();
    for (final UUID viewer : this.configuration.getViewers()) {
      if (!this.viewers.isLoaded(viewer)) {
        others.add(viewer);
        this.scheduled.remove(viewer);
        this.shown.remove(viewer);
      } else if (this.shown.contains(viewer)) {
        receiving.add(viewer);
      } else {
        others.add(viewer);
        if (this.scheduled.add(viewer)) {
          Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), () -> this.show(viewer));
        }
      }
    }
    this.recipients = receiving;
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
    this.shown.add(viewer);
    this.keyframeRequested = true;
  }

  /**
   * Gets the viewers that receive frames.
   *
   * @return the viewers shown the screen, as of the last {@link #update()}
   */
  public Set<UUID> getRecipients() {
    return this.recipients;
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
   * Sends one frame to the viewers that were shown the screen, as one bundle: its pages on the page maps, and with a
   * keyframe the anchors, which the server's own map data may have overwritten.
   *
   * @param frame the frame
   * @return the map colours sent to every recipient, or -1 if the frame has more pages than the screen has page
   *     slots, in which case nothing is sent and the next frame is asked to be a keyframe
   * @throws IllegalArgumentException if the bytes are not a valid MCV2 frame
   */
  public int send(final byte[] frame) {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    final boolean keyframe;
    final List<byte[]> pages;
    try {
      keyframe = FrameParser.parse(frame).isKeyframe();
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
    if (keyframe) {
      patches.addAll(this.screen.anchors());
    }
    MapPacketFactory.send(this.recipients, patches);
    return colors;
  }
}
