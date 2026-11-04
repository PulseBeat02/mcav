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
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Describes an MCV2 screen: a wall of maps in item frames, as {@code /mcav screen} builds it, on which players with
 * the MCV2 resource pack see the video decoded by the pack's shader, while everyone else sees the video dithered onto
 * the maps as usual.
 *
 * <p>The wall is {@code columns x rows} maps with consecutive ids from {@link #getMap()}, row by row from the top
 * left, in frames facing {@link #getFacing()}; {@link #getOrigin()} is the block the top-left frame hangs in. For
 * players with the pack, those maps carry anchors that tell the shader where the screen is, and hidden glowing frames
 * behind the wall carry the frames' pages on the page maps from {@link #getPageMap()} on. The default page maps lie
 * far above ordinary world allocations to avoid server map updates; callers must still ensure that those ids
 * are not allocated to other maps or streams.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed to
 * add or remove viewers while media is playing.
 *
 * <p>Configurations copy their origin and retain their viewer collection, encoder settings and optional pool.
 * Use a concurrent viewer collection when membership changes on another thread. The caller owns any explicitly
 * supplied encoder pool; neither the configuration nor a screen closes it. Creating a configuration does not
 * reserve map ids or build the visible wall.
 *
 * {@snippet :
 * final int firstMapId = 0;
 * final int columns = 15;
 * final int rows = 9;
 * final int videoWidth = 1920;
 * final int videoHeight = 1080;
 * final Location origin = topLeft.getLocation();
 * final BlockFace facing = topLeft.getFacing();
 * final Mcv2ConfigurationBuilder builder = Mcv2Configuration.builder();
 * final Mcv2Configuration requested = builder
 *   .viewers(viewers)
 *   .origin(origin)
 *   .facing(facing)
 *   .map(firstMapId)
 *   .columns(columns)
 *   .rows(rows)
 *   .video(videoWidth, videoHeight)
 *   .build();
 * }
 *
 * @since 1.0.0
 */
public final class Mcv2Configuration {

  /**
   * The largest wall side, in maps: an anchor names its column and row with one six-bit symbol each.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_SIDE = 63;

  /**
   * The most page slots a pack reserves.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_PAGE_SLOTS = 8;

  /**
   * The largest stream id: an anchor carries it in two six-bit symbols.
   *
   * @since 1.0.0
   */
  public static final long MAXIMUM_STREAM_ID = 4095L;

  private static final int DEFAULT_PAGE_SLOTS = MAXIMUM_PAGE_SLOTS;

  /**
   * The most frames a second a screen shows when none is set: the default live preset keeps up with 1080p video at 30
   * frames a second, but not at 60. A client decodes at most one frame for every frame it draws, so a screen faster
   * than its viewers' clients leaves them waiting for keyframes.
   *
   * @since 1.0.0
   */
  public static final double DEFAULT_MAXIMUM_FRAME_RATE = 30;

  /**
   * The highest rate a screen may be given, in frames a second.
   *
   * @since 1.0.0
   */
  public static final double MAXIMUM_FRAME_RATE = 240;

  /**
   * The default backlog threshold: 128 KiB of estimated map-update bytes, including patch overhead and anchors.
   * Its duration and number of buffered frames depend on encoded content and connection speed.
   *
   * @since 1.0.0
   */
  public static final long DEFAULT_BACKLOG_LIMIT = 128L * 1024;

  /**
   * The first page map id when none is set.
   *
   * @since 1.0.0
   */
  public static final int DEFAULT_PAGE_MAP = 2_000_000_000;

  /**
   * The stream id when none is set.
   *
   * @since 1.0.0
   */
  public static final long DEFAULT_STREAM_ID = 1L;

  /**
   * The cap on the bytes a viewer's operating system may hold unsent when none is set: 32 KiB, so a slow viewer's video
   * waits where the backlog limit sees it, and a game packet waits behind at most this much of it in the system.
   *
   * @since 1.0.0
   */
  public static final int DEFAULT_UNSENT_LIMIT = 32 * 1024;

  private static final int FACING_EAST = 0;
  private static final int FACING_SOUTH = 1;
  private static final int FACING_WEST = 2;
  private static final int FACING_NORTH = 3;

  private final Collection<UUID> viewers;
  private final Location origin;
  private final BlockFace facing;

  private final int map;
  private final int columns;
  private final int rows;

  private final int videoWidth;
  private final int videoHeight;

  private final int pageMap;
  private final int pageSlots;

  private final long streamId;
  private final long firstFrameId;
  private final double maximumFrameRate;

  private final Settings settings;
  private final NamedTextColor outlineColor;

  private final long backlogLimit;
  private final @Nullable Pool encoderPool;
  private final int unsentLimit;

  Mcv2Configuration(
    final DefaultMcv2ConfigurationBuilder builder,
    final Collection<UUID> viewers,
    final Location origin,
    final BlockFace facing,
    final int pageSlots
  ) {
    this.viewers = viewers;
    this.origin = origin.clone();
    this.facing = facing;
    this.map = builder.getMap();
    this.columns = builder.getColumns();
    this.rows = builder.getRows();
    this.videoWidth = videoSize(builder.getVideoWidth(), this.columns);
    this.videoHeight = videoSize(builder.getVideoHeight(), this.rows);
    this.pageMap = builder.getPageMap();
    this.pageSlots = pageSlots;
    this.streamId = builder.getStreamId();
    this.firstFrameId = builder.getFirstFrameId();
    this.maximumFrameRate = builder.getMaximumFrameRate();
    this.settings = builder.getSettings();
    this.outlineColor = builder.getOutlineColor();
    this.backlogLimit = builder.getBacklogLimit();
    this.encoderPool = builder.getEncoderPool();
    this.unsentLimit = builder.getUnsentLimit();
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static Mcv2ConfigurationBuilder builder() {
    return new DefaultMcv2ConfigurationBuilder();
  }

  static int videoSize(final int size, final int maps) {
    return size > 0 ? size : MapLayout.MAP_SIZE * maps;
  }

  static int defaultPageSlots(final int maps) {
    return Math.min(DEFAULT_PAGE_SLOTS, maps);
  }

  /**
   * Copies the screen with another video size, for a screen that steps down to a smaller video, or back up.
   *
   * @param width  the video width, 1 to 4096
   * @param height the video height, 1 to 4096
   * @return the copy
   * @throws IllegalArgumentException if the size is out of range
   * @since 1.0.0
   */
  public Mcv2Configuration withVideo(final int width, final int height) {
    Preconditions.checkArgument(width >= 1 && height >= 1, "Video size must be positive!");

    return this.toBuilder().video(width, height).build();
  }

  /**
   * Copies the screen with what the resource pack's server gave it with its slot of the pack: the slot's stream id and
   * page maps, and the id of the screen's first frame.
   *
   * @param streamId     the stream id, 0 to {@value #MAXIMUM_STREAM_ID}
   * @param pageMap      the first page map id, not negative
   * @param firstFrameId the id of the screen's first frame, an unsigned 32-bit value
   * @return the copy
   * @throws IllegalArgumentException if a value is out of range, or a page map is a map of the wall
   * @since 1.0.0
   */
  public Mcv2Configuration withSlot(final long streamId, final int pageMap, final long firstFrameId) {
    return this.toBuilder().streamId(streamId).pageMap(pageMap).firstFrameId(firstFrameId).build();
  }

  private Mcv2ConfigurationBuilder toBuilder() {
    final Mcv2ConfigurationBuilder builder = builder()
      .viewers(this.viewers)
      .origin(this.origin)
      .facing(this.facing)
      .map(this.map)
      .columns(this.columns)
      .rows(this.rows)
      .video(this.videoWidth, this.videoHeight)
      .pageMap(this.pageMap)
      .pageSlots(this.pageSlots)
      .streamId(this.streamId)
      .firstFrameId(this.firstFrameId)
      .maximumFrameRate(this.maximumFrameRate)
      .settings(this.settings)
      .outlineColor(this.outlineColor)
      .backlogLimit(this.backlogLimit)
      .unsentLimit(this.unsentLimit);
    final Pool pool = this.encoderPool;
    if (pool != null) {
      builder.encoderPool(pool);
    }

    return builder;
  }

  /**
   * Returns the encoder budget the screen encodes in: the one it was given, or the server's shared budget.
   *
   * @return the budget
   * @since 1.0.0
   */
  public Pool getEncoderPool() {
    final Pool pool = this.encoderPool;
    return pool != null ? pool : Pool.shared();
  }

  /**
   * Returns the players who watch the screen.
   *
   * @return the UUIDs of the viewers, not copied
   * @since 1.0.0
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Returns the block the top-left frame of the wall hangs in.
   *
   * @return a copy of the location
   * @since 1.0.0
   */
  public Location getOrigin() {
    return this.origin.clone();
  }

  /**
   * Returns the direction the frames of the wall face, towards the viewers.
   *
   * @return north, south, east or west
   * @since 1.0.0
   */
  public BlockFace getFacing() {
    return this.facing;
  }

  /**
   * Returns the direction the maps' right edge points to, as seen by a viewer in front of the wall.
   *
   * @return the direction columns count along
   * @since 1.0.0
   */
  public BlockFace getRight() {
    return switch (this.facing) {
      case SOUTH -> BlockFace.EAST;
      case WEST -> BlockFace.SOUTH;
      case NORTH -> BlockFace.WEST;
      case EAST -> BlockFace.NORTH;
      case
        UP,
        DOWN,
        NORTH_EAST,
        NORTH_WEST,
        SOUTH_EAST,
        SOUTH_WEST,
        WEST_NORTH_WEST,
        NORTH_NORTH_WEST,
        NORTH_NORTH_EAST,
        EAST_NORTH_EAST,
        EAST_SOUTH_EAST,
        SOUTH_SOUTH_EAST,
        SOUTH_SOUTH_WEST,
        WEST_SOUTH_WEST,
        SELF -> {
        final String message = "Facing %s is not a wall direction!".formatted(this.facing);
        throw new IllegalStateException(message);
      }
    };
  }

  /**
   * Returns the anchor's facing code: 0 when the maps' right edge points east, 1 south, 2 west and 3 north.
   *
   * @return the code the shader reads
   * @since 1.0.0
   */
  public int getFacingCode() {
    final BlockFace right = this.getRight();
    return switch (right) {
      case EAST -> FACING_EAST;
      case SOUTH -> FACING_SOUTH;
      case WEST -> FACING_WEST;
      case NORTH -> FACING_NORTH;
      case
        UP,
        DOWN,
        NORTH_EAST,
        NORTH_WEST,
        SOUTH_EAST,
        SOUTH_WEST,
        WEST_NORTH_WEST,
        NORTH_NORTH_WEST,
        NORTH_NORTH_EAST,
        EAST_NORTH_EAST,
        EAST_SOUTH_EAST,
        SOUTH_SOUTH_EAST,
        SOUTH_SOUTH_WEST,
        WEST_SOUTH_WEST,
        SELF -> {
        final String message = "Right edge %s is not a wall direction!".formatted(right);
        throw new IllegalStateException(message);
      }
    };
  }

  /**
   * Returns the id of the top-left map of the wall.
   *
   * @return the first map id
   * @since 1.0.0
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Returns the width of the wall in maps.
   *
   * @return the columns
   * @since 1.0.0
   */
  public int getColumns() {
    return this.columns;
  }

  /**
   * Returns the height of the wall in maps.
   *
   * @return the rows
   * @since 1.0.0
   */
  public int getRows() {
    return this.rows;
  }

  /**
   * Returns the width of the encoded video; defaults to the wall's native width, 128 pixels per map.
   *
   * @return the width in pixels
   * @since 1.0.0
   */
  public int getVideoWidth() {
    return this.videoWidth;
  }

  /**
   * Returns the height of the encoded video; defaults to the wall's native height, 128 pixels per map.
   *
   * @return the height in pixels
   * @since 1.0.0
   */
  public int getVideoHeight() {
    return this.videoHeight;
  }

  /**
   * Returns the id of the map that carries the first page of every frame; page p is on map {@code getPageMap() + p}.
   *
   * @return the first page map id
   * @since 1.0.0
   */
  public int getPageMap() {
    return this.pageMap;
  }

  /**
   * Returns how many pages a frame may have; a frame with more is not sent. Defaults to {@value #MAXIMUM_PAGE_SLOTS}, or the
   * number of maps of a smaller wall, because behind every map of the wall hangs one page's frame.
   *
   * @return the page slots
   * @since 1.0.0
   */
  public int getPageSlots() {
    return this.pageSlots;
  }

  /**
   * Returns the stream id every page carries; the pack decodes only its own stream.
   *
   * @return the configured stream id, from 0 through {@value #MAXIMUM_STREAM_ID}
   * @since 1.0.0
   */
  public long getStreamId() {
    return this.streamId;
  }

  /**
   * Returns the encoder profile.
   *
   * @return the settings
   * @since 1.0.0
   */
  public Settings getSettings() {
    return this.settings;
  }

  /**
   * Returns the team colour of the hidden page frames. They glow, which is what makes the client run the pack's post
   * chain, and the pack removes outlines of exactly this colour, so glowing entities of other teams with this colour
   * lose their outline too; the default, dark purple, is rarely used for glowing. It is never black, whose RGB value
   * of zero means "no outline" to the client.
   *
   * @return the colour
   * @since 1.0.0
   */
  public NamedTextColor getOutlineColor() {
    return this.outlineColor;
  }

  /**
   * Returns how much video a viewer's connection may have left to write for the viewer to be sent another frame, in estimated
   * map-update bytes including patch overhead. A viewer over it misses frames until one it can decode comes with its backlog under it again (see
   * {@link Mcv2Link}), so a slow connection neither delays the other viewers nor piles video in front of its own
   * game packets.
   *
   * @return the limit in bytes
   * @since 1.0.0
   */
  public long getBacklogLimit() {
    return this.backlogLimit;
  }

  /**
   * Returns the id of the screen's first frame: the frames after it count up from it, so a screen that takes over the
   * pack slot of an earlier one is newer to the clients than anything the earlier one sent.
   *
   * @return an unsigned 32-bit value
   * @since 1.0.0
   */
  public long getFirstFrameId() {
    return this.firstFrameId;
  }

  /**
   * Returns the most frames a second the screen shows; a faster source is thinned to it.
   *
   * @return the rate, at most {@value #MAXIMUM_FRAME_RATE}, or 0 for every frame the source gives
   * @since 1.0.0
   */
  public double getMaximumFrameRate() {
    return this.maximumFrameRate;
  }

  /**
   * Returns the cap on the bytes a viewer's operating system may hold unsent, which is set on a viewer's connection when
   * it starts receiving the screen (on Linux, where Paper uses the epoll transport).
   *
   * @return the cap in bytes, or 0 to leave the system's own
   * @since 1.0.0
   */
  public int getUnsentLimit() {
    return this.unsentLimit;
  }
}
