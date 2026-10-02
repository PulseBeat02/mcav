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
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
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
 */
public final class Mcv2Configuration {

  /** The largest wall side, in maps: an anchor names its column and row with one six-bit symbol each. */
  public static final int MAX_SIDE = 63;

  /** The most page slots a pack reserves. */
  public static final int MAX_PAGE_SLOTS = 8;

  /** The largest stream id: an anchor carries it in two six-bit symbols. */
  public static final long MAX_STREAM_ID = 4095;

  /**
   * The page slots of a screen that configures none, when it has that many maps: all of them, 98 KB a frame. A live
   * gameplay frame takes 46 KB on average at 60 fps, a keyframe more, which the encoder brings under the slots' bound.
   */
  private static final int DEFAULT_PAGE_SLOTS = MAX_PAGE_SLOTS;

  /**
   * The most frames a second a screen shows when none is set: the rate whose 1080p gate the default live preset met
   * (the 1080p60 gate was not met). A client decodes at most one frame for every frame it draws, so a screen faster
   * than its viewers' clients leaves them waiting for keyframes.
   */
  public static final double DEFAULT_MAX_FRAME_RATE = 30;

  /** The highest rate a screen may be given, in frames a second. */
  public static final double MAX_FRAME_RATE = 240;

  /**
   * The default backlog threshold: 128 KiB of estimated map-update bytes, including patch overhead and anchors.
   * Its duration and number of buffered frames depend on encoded content and connection speed.
   */
  public static final long DEFAULT_BACKLOG_LIMIT = 128 * 1024;

  /** The first page map id when none is set. */
  public static final int DEFAULT_PAGE_MAP = 2_000_000_000;

  /**
   * The cap on the bytes a viewer's operating system may hold unsent when none is set: 32 KiB, so a slow viewer's video
   * waits where the backlog limit sees it, and a game packet waits behind at most this much of it in the system.
   */
  public static final int DEFAULT_UNSENT_LIMIT = 32 * 1024;

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

  private final double maxFrameRate;

  private final EncoderSettings settings;

  private final NamedTextColor outlineColor;

  private final long backlogLimit;

  private final @Nullable EncoderPool encoderPool;

  private final int unsentLimit;

  private Mcv2Configuration(
    final Builder builder,
    final Collection<UUID> viewers,
    final Location origin,
    final BlockFace facing,
    final int pageSlots
  ) {
    this.viewers = viewers;
    this.origin = origin.clone();
    this.facing = facing;
    this.map = builder.map;
    this.columns = builder.columns;
    this.rows = builder.rows;
    this.videoWidth = builder.videoWidth > 0 ? builder.videoWidth : MapLayout.MAP_SIZE * builder.columns;
    this.videoHeight = builder.videoHeight > 0 ? builder.videoHeight : MapLayout.MAP_SIZE * builder.rows;
    this.pageMap = builder.pageMap;
    this.pageSlots = pageSlots;
    this.streamId = builder.streamId;
    this.firstFrameId = builder.firstFrameId;
    this.maxFrameRate = builder.maxFrameRate;
    this.settings = builder.settings;
    this.outlineColor = builder.outlineColor;
    this.backlogLimit = builder.backlogLimit;
    this.encoderPool = builder.encoderPool;
    this.unsentLimit = builder.unsentLimit;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the players who watch the screen.
   *
   * @return the UUIDs of the viewers, not copied
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the block the top-left frame of the wall hangs in.
   *
   * @return a copy of the location
   */
  public Location getOrigin() {
    return this.origin.clone();
  }

  /**
   * Gets the direction the frames of the wall face, towards the viewers.
   *
   * @return north, south, east or west
   */
  public BlockFace getFacing() {
    return this.facing;
  }

  /**
   * Gets the direction the maps' right edge points to, as seen by a viewer in front of the wall.
   *
   * @return the direction columns count along
   */
  public BlockFace getRight() {
    return switch (this.facing) {
      case SOUTH -> BlockFace.EAST;
      case WEST -> BlockFace.SOUTH;
      case NORTH -> BlockFace.WEST;
      default -> BlockFace.NORTH;
    };
  }

  /**
   * Gets the anchor's facing code: 0 when the maps' right edge points east, 1 south, 2 west and 3 north.
   *
   * @return the code the shader reads
   */
  public int getFacingCode() {
    return switch (this.getRight()) {
      case EAST -> 0;
      case SOUTH -> 1;
      case WEST -> 2;
      default -> 3;
    };
  }

  /**
   * Gets the id of the top-left map of the wall.
   *
   * @return the first map id
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Gets the width of the wall in maps.
   *
   * @return the columns
   */
  public int getColumns() {
    return this.columns;
  }

  /**
   * Gets the height of the wall in maps.
   *
   * @return the rows
   */
  public int getRows() {
    return this.rows;
  }

  /**
   * Gets the width of the encoded video; defaults to the wall's native width, 128 pixels per map.
   *
   * @return the width in pixels
   */
  public int getVideoWidth() {
    return this.videoWidth;
  }

  /**
   * Gets the height of the encoded video; defaults to the wall's native height, 128 pixels per map.
   *
   * @return the height in pixels
   */
  public int getVideoHeight() {
    return this.videoHeight;
  }

  /**
   * Gets the id of the map that carries the first page of every frame; page p is on map {@code getPageMap() + p}.
   *
   * @return the first page map id
   */
  public int getPageMap() {
    return this.pageMap;
  }

  /**
   * Gets how many pages a frame may have; a frame with more is not sent. Defaults to four, or the number of maps of
   * a smaller wall, because behind every map of the wall hangs one page's frame.
   *
   * @return the page slots
   */
  public int getPageSlots() {
    return this.pageSlots;
  }

  /**
   * Gets the stream id every page carries; the pack decodes only its own stream.
   *
   * @return the configured stream id, from 0 through {@value #MAX_STREAM_ID}
   */
  public long getStreamId() {
    return this.streamId;
  }

  /**
   * Gets the encoder profile.
   *
   * @return the settings
   */
  public EncoderSettings getSettings() {
    return this.settings;
  }

  /**
   * Gets the team colour of the hidden page frames. They glow, which is what makes the client run the pack's post
   * chain, and the pack removes outlines of exactly this colour, so glowing entities of other teams with this colour
   * lose their outline too; the default, dark purple, is rarely used for glowing. It is never black, whose RGB value
   * of zero means "no outline" to the client.
   *
   * @return the colour
   */
  public NamedTextColor getOutlineColor() {
    return this.outlineColor;
  }

  /**
   * Gets how much video a viewer's connection may have left to write for the viewer to be sent another frame, in estimated
   * map-update bytes including patch overhead. A viewer over it misses frames until one it can decode comes with its backlog under it again (see
   * {@link Mcv2Link}), so a slow connection neither delays the other viewers nor piles video in front of its own
   * game packets.
   *
   * @return the limit in bytes
   */
  public long getBacklogLimit() {
    return this.backlogLimit;
  }

  /**
   * Copies the screen with another video size, for a screen that steps down to a smaller video, or back up.
   *
   * @param width  the video width, 1 to 4096
   * @param height the video height, 1 to 4096
   * @return the copy
   * @throws IllegalArgumentException if the size is out of range
   */
  public Mcv2Configuration withVideo(final int width, final int height) {
    Preconditions.checkArgument(width >= 1 && height >= 1, "Video size must be positive");
    return this.toBuilder().video(width, height).build();
  }

  /**
   * Copies the screen with what the resource pack's server gave it with its slot of the pack: the slot's stream id and
   * page maps, and the id of the screen's first frame.
   *
   * @param streamId     the stream id, 0 to {@value #MAX_STREAM_ID}
   * @param pageMap      the first page map id, not negative
   * @param firstFrameId the id of the screen's first frame, an unsigned 32-bit value
   * @return the copy
   * @throws IllegalArgumentException if a value is out of range, or a page map is a map of the wall
   */
  public Mcv2Configuration withSlot(final long streamId, final int pageMap, final long firstFrameId) {
    return this.toBuilder().streamId(streamId).pageMap(pageMap).firstFrameId(firstFrameId).build();
  }

  private Builder toBuilder() {
    final Builder builder = builder()
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
      .maxFrameRate(this.maxFrameRate)
      .settings(this.settings)
      .outlineColor(this.outlineColor)
      .backlogLimit(this.backlogLimit)
      .unsentLimit(this.unsentLimit);
    final EncoderPool pool = this.encoderPool;
    if (pool != null) {
      builder.encoderPool(pool);
    }
    return builder;
  }

  /**
   * Gets the id of the screen's first frame: the frames after it count up from it, so a screen that takes over the
   * pack slot of an earlier one is newer to the clients than anything the earlier one sent.
   *
   * @return an unsigned 32-bit value
   */
  public long getFirstFrameId() {
    return this.firstFrameId;
  }

  /**
   * Gets the most frames a second the screen shows; a faster source is thinned to it.
   *
   * @return the rate, at most {@value #MAX_FRAME_RATE}, or 0 for every frame the source gives
   */
  public double getMaxFrameRate() {
    return this.maxFrameRate;
  }

  /**
   * Gets the cap on the bytes a viewer's operating system may hold unsent, which is set on a viewer's connection when
   * it starts receiving the screen (on Linux, where Paper uses the epoll transport).
   *
   * @return the cap in bytes, or 0 to leave the system's own
   */
  public int getUnsentLimit() {
    return this.unsentLimit;
  }

  /**
   * Gets the encoder budget the screen encodes in: the one it was given, or the server's shared budget.
   *
   * @return the budget
   */
  public EncoderPool getEncoderPool() {
    final EncoderPool pool = this.encoderPool;
    return pool != null ? pool : EncoderPool.shared();
  }

  /**
   * Builds MCV2 screen configurations. The viewers, the origin, the facing, the map id and the wall size are
   * required.
   *
   * <p>Builders are mutable and not thread-safe. Numeric setters generally defer validation to {@link #build()}.
   * Origin, facing and outline-color setters validate immediately. A zero video dimension selects 128 times the
   * corresponding wall dimension; keep the resolved video within the codec's 4096-pixel limit, which is not checked
   * again after resolving that default.
   */
  public static final class Builder {

    private @MonotonicNonNull Collection<UUID> viewers;

    private @MonotonicNonNull Location origin;

    private @MonotonicNonNull BlockFace facing;

    private int map = -1;

    private int columns;

    private int rows;

    private int videoWidth;

    private int videoHeight;

    private int pageMap = DEFAULT_PAGE_MAP;

    private int pageSlots;

    private long streamId = 1;

    private long firstFrameId;

    private double maxFrameRate = DEFAULT_MAX_FRAME_RATE;

    private long backlogLimit = DEFAULT_BACKLOG_LIMIT;

    private @Nullable EncoderPool encoderPool;

    private int unsentLimit = DEFAULT_UNSENT_LIMIT;

    private EncoderSettings settings = EncoderSettings.LIVE;

    private NamedTextColor outlineColor = NamedTextColor.DARK_PURPLE;

    Builder() {
      // created through Mcv2Configuration.builder()
    }

    /**
     * Sets the players who watch the screen. The collection is not copied.
     *
     * @param viewers the UUIDs of the viewers
     * @return this builder
     * @throws NullPointerException if {@code viewers} is null
     */
    public Builder viewers(final Collection<UUID> viewers) {
      Preconditions.checkNotNull(viewers, "Viewers must not be null");
      this.viewers = viewers;
      return this;
    }

    /**
     * Sets the block the top-left frame of the wall hangs in.
     *
     * <p>The location is copied to block coordinates; subsequent caller mutations do not move the screen.
     *
     * @param origin the location of that block, in a world
     * @return this builder
     * @throws IllegalArgumentException if the origin has no world
     * @throws NullPointerException if {@code origin} is null
     */
    public Builder origin(final Location origin) {
      Preconditions.checkNotNull(origin, "Origin must not be null");
      Preconditions.checkArgument(origin.getWorld() != null, "Origin must be in a world");
      this.origin = origin.toBlockLocation();
      return this;
    }

    /**
     * Sets the direction the frames of the wall face.
     *
     * @param facing north, south, east or west
     * @return this builder
     * @throws IllegalArgumentException if the direction is not horizontal and cardinal
     * @throws NullPointerException if {@code facing} is null
     */
    public Builder facing(final BlockFace facing) {
      Preconditions.checkNotNull(facing, "Facing must not be null");
      Preconditions.checkArgument(facing.isCartesian() && facing.getModY() == 0, "Frames must face a horizontal direction: %s", facing);
      this.facing = facing;
      return this;
    }

    /**
     * Sets the id of the top-left map of the wall.
     *
     * @param map the first map id, not negative
     * @return this builder
     */
    public Builder map(final int map) {
      this.map = map;
      return this;
    }

    /**
     * Sets the width of the wall in maps.
     *
     * @param columns 1 to {@link #MAX_SIDE}
     * @return this builder
     */
    public Builder columns(final int columns) {
      this.columns = columns;
      return this;
    }

    /**
     * Sets the height of the wall in maps.
     *
     * @param rows 1 to {@link #MAX_SIDE}
     * @return this builder
     */
    public Builder rows(final int rows) {
      this.rows = rows;
      return this;
    }

    /**
     * Sets the size of the encoded video. Frames are resized to it before they are encoded.
     *
     * @param width  1 to 4096 pixels, or 0 for the wall's native width
     * @param height 1 to 4096 pixels, or 0 for the wall's native height
     * @return this builder
     */
    public Builder video(final int width, final int height) {
      this.videoWidth = width;
      this.videoHeight = height;
      return this;
    }

    /**
     * Sets the id of the map carrying the first page of every frame.
     *
     * @param pageMap the first page map id, not negative
     * @return this builder
     */
    public Builder pageMap(final int pageMap) {
      this.pageMap = pageMap;
      return this;
    }

    /**
     * Sets how many pages a frame may have.
     *
     * @param pageSlots 1 to {@link #MAX_PAGE_SLOTS}, or 0 for the default
     * @return this builder
     */
    public Builder pageSlots(final int pageSlots) {
      this.pageSlots = pageSlots;
      return this;
    }

    /**
     * Sets the stream id every page and anchor carries, which tells the resource pack which of its screens they are.
     *
     * @param streamId 0 to {@value #MAX_STREAM_ID}: an anchor carries it in two six-bit symbols
     * @return this builder
     */
    public Builder streamId(final long streamId) {
      this.streamId = streamId;
      return this;
    }

    /**
     * Sets the id of the screen's first frame; defaults to 0.
     *
     * @param firstFrameId an unsigned 32-bit value
     * @return this builder
     */
    public Builder firstFrameId(final long firstFrameId) {
      this.firstFrameId = firstFrameId;
      return this;
    }

    /**
     * Sets the most frames a second the screen shows; defaults to {@link #DEFAULT_MAX_FRAME_RATE}.
     *
     * @param maxFrameRate a finite rate from 0 through {@value #MAX_FRAME_RATE} frames per second;
     *                     0 accepts every source frame
     * @return this builder
     */
    public Builder maxFrameRate(final double maxFrameRate) {
      this.maxFrameRate = maxFrameRate;
      return this;
    }

    /**
     * Sets the encoder profile; defaults to {@link EncoderSettings#LIVE}: a screen encodes a source while it plays, and
     * live is the slowest rung of the preset ladder that keeps the 95th percentile of a 1080p30 frame under 32 ms with 12
     * encoder threads of a 6-core server, on quiet content and on gameplay alike. With the default budget of 6 threads
     * gameplay needs the next rung, {@link EncoderSettings#LIVE_ADAPTIVE}, and no rung encodes 1080p60 frames that fast
     * there: a screen steps down the ladder, to adaptive, then {@code live-fast}, then fewer frames, when it cannot keep
     * up ({@link EncoderSettings#faster()}).
     *
     * @param settings the settings
     * @return this builder
     * @throws NullPointerException if {@code settings} is null
     */
    public Builder settings(final EncoderSettings settings) {
      Preconditions.checkNotNull(settings, "Settings must not be null");
      this.settings = settings;
      return this;
    }

    /**
     * Sets the team colour of the hidden page frames; defaults to dark purple.
     *
     * @param outlineColor the colour, not black: an outline colour of zero means no outline, so the client would not
     *                     run the pack's post chain
     * @return this builder
     * @throws IllegalArgumentException if the color is black
     * @throws NullPointerException if {@code outlineColor} is null
     */
    public Builder outlineColor(final NamedTextColor outlineColor) {
      Preconditions.checkNotNull(outlineColor, "Outline color must not be null");
      Preconditions.checkArgument(outlineColor.value() != 0, "The outline colour must not be black");
      this.outlineColor = outlineColor;
      return this;
    }

    /**
     * Sets how much video a viewer's connection may have left to write for the viewer to be sent another frame;
     * defaults to {@link #DEFAULT_BACKLOG_LIMIT}.
     *
     * @param backlogLimit the nonnegative threshold in estimated map-update bytes, including patch overhead;
     *                     zero allows a new frame only once the tracked backlog has drained
     * @return this builder
     */
    public Builder backlogLimit(final long backlogLimit) {
      this.backlogLimit = backlogLimit;
      return this;
    }

    /**
     * Sets the cap on the bytes a viewer's operating system may hold unsent, {@link #DEFAULT_UNSENT_LIMIT} unless set.
     *
     * @param unsentLimit the cap in bytes, or 0 to leave the system's own, for example with no backlog limit either
     * @return this builder
     */
    public Builder unsentLimit(final int unsentLimit) {
      this.unsentLimit = unsentLimit;
      return this;
    }

    /**
     * Sets the encoder budget the screen encodes in, instead of the server's shared budget, {@link EncoderPool#shared()}.
     * Screens that encode at the same time share a budget's threads.
     *
     * @param encoderPool the budget
     * @return this builder
     * @throws NullPointerException if {@code encoderPool} is null
     */
    public Builder encoderPool(final EncoderPool encoderPool) {
      this.encoderPool = Preconditions.checkNotNull(encoderPool, "Encoder pool must not be null");
      return this;
    }

    /**
     * Builds the configuration. The builder is not changed.
     *
     * @return the configuration
     * @throws NullPointerException     if the viewers, the origin or the facing were not set
     * @throws IllegalArgumentException if a value is out of range
     */
    public Mcv2Configuration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");
      final Location configuredOrigin = Preconditions.checkNotNull(this.origin, "Origin must be set");
      final BlockFace configuredFacing = Preconditions.checkNotNull(this.facing, "Facing must be set");
      Preconditions.checkArgument(this.map >= 0, "Map id must be set and non-negative");
      Preconditions.checkArgument(this.columns >= 1 && this.columns <= MAX_SIDE, "Columns must be 1 to %s", MAX_SIDE);
      Preconditions.checkArgument(this.rows >= 1 && this.rows <= MAX_SIDE, "Rows must be 1 to %s", MAX_SIDE);
      Preconditions.checkArgument(this.videoWidth >= 0 && this.videoWidth <= Mcv2Format.MAX_DIMENSION, "Video width must be 0 to 4096");
      Preconditions.checkArgument(this.videoHeight >= 0 && this.videoHeight <= Mcv2Format.MAX_DIMENSION, "Video height must be 0 to 4096");
      Preconditions.checkArgument(this.pageSlots >= 0 && this.pageSlots <= MAX_PAGE_SLOTS, "Page slots must be 0 to %s", MAX_PAGE_SLOTS);
      Preconditions.checkArgument(this.streamId >= 0 && this.streamId <= MAX_STREAM_ID, "Stream id must be 0 to %s", MAX_STREAM_ID);
      Preconditions.checkArgument(
        this.firstFrameId >= 0 && this.firstFrameId <= Mcv2Format.MAX_U32,
        "First frame id must be an unsigned 32-bit value"
      );
      Preconditions.checkArgument(
        this.maxFrameRate >= 0 && this.maxFrameRate <= MAX_FRAME_RATE,
        "Frame rate must be 0 to %s",
        MAX_FRAME_RATE
      );
      Preconditions.checkArgument(this.backlogLimit >= 0, "Backlog limit must not be negative");
      Preconditions.checkArgument(this.unsentLimit >= 0, "Unsent limit must not be negative");
      final int slots = this.pageSlots > 0 ? this.pageSlots : Math.min(DEFAULT_PAGE_SLOTS, this.columns * this.rows);
      final long lastMap = (long) this.map + (long) this.columns * this.rows - 1;
      final long lastPage = (long) this.pageMap + slots - 1;
      Preconditions.checkArgument(this.pageMap >= 0 && lastPage <= Integer.MAX_VALUE, "Page map ids must be non-negative ints");
      Preconditions.checkArgument(lastMap <= Integer.MAX_VALUE, "Map ids exceed the integer range");
      Preconditions.checkArgument(lastPage < this.map || this.pageMap > lastMap, "Page maps must not be maps of the wall");
      return new Mcv2Configuration(this, configuredViewers, configuredOrigin, configuredFacing, slots);
    }
  }
}
