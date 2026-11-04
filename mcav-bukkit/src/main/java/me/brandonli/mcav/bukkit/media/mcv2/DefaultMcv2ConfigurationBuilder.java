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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultMcv2ConfigurationBuilder implements Mcv2ConfigurationBuilder {

  private static final int UNSET_MAP = -1;

  private @MonotonicNonNull Collection<UUID> viewers;
  private @MonotonicNonNull Location origin;
  private @MonotonicNonNull BlockFace facing;

  private int map;
  private int columns;
  private int rows;

  private int videoWidth;
  private int videoHeight;

  private int pageMap;
  private int pageSlots;

  private long streamId;
  private long firstFrameId;
  private double maximumFrameRate;
  private long backlogLimit;
  private @Nullable Pool encoderPool;
  private int unsentLimit;

  private Settings settings;
  private NamedTextColor outlineColor;

  DefaultMcv2ConfigurationBuilder() {
    this.map = UNSET_MAP;
    this.pageMap = Mcv2Configuration.DEFAULT_PAGE_MAP;
    this.streamId = Mcv2Configuration.DEFAULT_STREAM_ID;
    this.maximumFrameRate = Mcv2Configuration.DEFAULT_MAXIMUM_FRAME_RATE;
    this.backlogLimit = Mcv2Configuration.DEFAULT_BACKLOG_LIMIT;
    this.unsentLimit = Mcv2Configuration.DEFAULT_UNSENT_LIMIT;
    this.settings = Settings.DEFAULT;
    this.outlineColor = NamedTextColor.DARK_PURPLE;
  }

  @Override
  public Mcv2ConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder origin(final Location origin) {
    Preconditions.checkNotNull(origin, "Origin must not be null!");
    Preconditions.checkArgument(origin.getWorld() != null, "Origin must be in a world!");

    this.origin = origin.toBlockLocation();
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder facing(final BlockFace facing) {
    Preconditions.checkNotNull(facing, "Facing must not be null!");
    Preconditions.checkArgument(
      facing.isCartesian() && facing.getModY() == 0,
      "Facing %s is not horizontal, but frames must face a horizontal direction!",
      facing
    );

    this.facing = facing;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder map(final int map) {
    this.map = map;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder columns(final int columns) {
    this.columns = columns;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder rows(final int rows) {
    this.rows = rows;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder video(final int width, final int height) {
    this.videoWidth = width;
    this.videoHeight = height;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder pageMap(final int pageMap) {
    this.pageMap = pageMap;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder pageSlots(final int pageSlots) {
    this.pageSlots = pageSlots;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder streamId(final long streamId) {
    this.streamId = streamId;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder firstFrameId(final long firstFrameId) {
    this.firstFrameId = firstFrameId;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder maximumFrameRate(final double maximumFrameRate) {
    this.maximumFrameRate = maximumFrameRate;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder settings(final Settings settings) {
    Preconditions.checkNotNull(settings, "Settings must not be null!");

    this.settings = settings;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder outlineColor(final NamedTextColor outlineColor) {
    Preconditions.checkNotNull(outlineColor, "Outline color must not be null!");
    Preconditions.checkArgument(outlineColor.value() != 0, "Outline color must not be black!");

    this.outlineColor = outlineColor;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder backlogLimit(final long backlogLimit) {
    this.backlogLimit = backlogLimit;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder unsentLimit(final int unsentLimit) {
    this.unsentLimit = unsentLimit;
    return this;
  }

  @Override
  public Mcv2ConfigurationBuilder encoderPool(final Pool encoderPool) {
    this.encoderPool = Preconditions.checkNotNull(encoderPool, "Encoder pool must not be null!");
    return this;
  }

  @Override
  public Mcv2Configuration build() {
    final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set!");
    final Location configuredOrigin = Preconditions.checkNotNull(this.origin, "Origin must be set!");
    final BlockFace configuredFacing = Preconditions.checkNotNull(this.facing, "Facing must be set!");
    Preconditions.checkArgument(this.map >= 0, "Map id must be set and non-negative!");
    Preconditions.checkArgument(
      this.columns >= 1 && this.columns <= Mcv2Configuration.MAXIMUM_SIDE,
      "Columns must be between 1 and %s inclusive!",
      Mcv2Configuration.MAXIMUM_SIDE
    );
    Preconditions.checkArgument(
      this.rows >= 1 && this.rows <= Mcv2Configuration.MAXIMUM_SIDE,
      "Rows must be between 1 and %s inclusive!",
      Mcv2Configuration.MAXIMUM_SIDE
    );
    Preconditions.checkArgument(
      this.videoWidth >= 0 && this.videoWidth <= Mcv2Decoder.MAXIMUM_DIMENSION,
      "Video width must be between 0 and %s inclusive!",
      Mcv2Decoder.MAXIMUM_DIMENSION
    );
    Preconditions.checkArgument(
      this.videoHeight >= 0 && this.videoHeight <= Mcv2Decoder.MAXIMUM_DIMENSION,
      "Video height must be between 0 and %s inclusive!",
      Mcv2Decoder.MAXIMUM_DIMENSION
    );

    final int width = Mcv2Configuration.videoSize(this.videoWidth, this.columns);
    final int height = Mcv2Configuration.videoSize(this.videoHeight, this.rows);
    Preconditions.checkArgument(
      width <= Mcv2Decoder.MAXIMUM_DIMENSION && height <= Mcv2Decoder.MAXIMUM_DIMENSION,
      "A wall of %s by %s maps is %s by %s pixels, more than the codec's %s: set a smaller video size!",
      this.columns,
      this.rows,
      width,
      height,
      Mcv2Decoder.MAXIMUM_DIMENSION
    );
    Preconditions.checkArgument(
      this.pageSlots >= 0 && this.pageSlots <= Mcv2Configuration.MAXIMUM_PAGE_SLOTS,
      "Page slots must be between 0 and %s inclusive!",
      Mcv2Configuration.MAXIMUM_PAGE_SLOTS
    );
    Preconditions.checkArgument(
      this.streamId >= 0 && this.streamId <= Mcv2Configuration.MAXIMUM_STREAM_ID,
      "Stream id must be between 0 and %s inclusive!",
      Mcv2Configuration.MAXIMUM_STREAM_ID
    );
    Preconditions.checkArgument(
      this.firstFrameId >= 0 && this.firstFrameId <= Mcv2Decoder.MAXIMUM_UNSIGNED_INT,
      "First frame id must be an unsigned 32-bit value!"
    );
    Preconditions.checkArgument(
      this.maximumFrameRate >= 0 && this.maximumFrameRate <= Mcv2Configuration.MAXIMUM_FRAME_RATE,
      "Frame rate must be between 0 and %s inclusive!",
      Mcv2Configuration.MAXIMUM_FRAME_RATE
    );
    Preconditions.checkArgument(this.backlogLimit >= 0, "Backlog limit must not be negative!");
    Preconditions.checkArgument(this.unsentLimit >= 0, "Unsent limit must not be negative!");

    final int maps = this.columns * this.rows;
    final int slots = this.pageSlots > 0 ? this.pageSlots : Mcv2Configuration.defaultPageSlots(maps);
    // A slot without a wall map is invisible to the client and prevents frame reassembly.
    Preconditions.checkArgument(
      slots <= maps,
      "A wall of %s maps cannot carry %s page slots: one page frame hangs behind each map!",
      maps,
      slots
    );

    final long lastMap = (long) this.map + (long) this.columns * this.rows - 1;
    final long lastPage = (long) this.pageMap + slots - 1;
    Preconditions.checkArgument(this.pageMap >= 0 && lastPage <= Integer.MAX_VALUE, "Page map ids must be non-negative ints!");
    Preconditions.checkArgument(lastMap <= Integer.MAX_VALUE, "Map ids exceed the integer range!");
    Preconditions.checkArgument(lastPage < this.map || this.pageMap > lastMap, "Page maps must not be maps of the wall!");

    return new Mcv2Configuration(this, configuredViewers, configuredOrigin, configuredFacing, slots);
  }

  int getMap() {
    return this.map;
  }

  int getColumns() {
    return this.columns;
  }

  int getRows() {
    return this.rows;
  }

  int getVideoWidth() {
    return this.videoWidth;
  }

  int getVideoHeight() {
    return this.videoHeight;
  }

  int getPageMap() {
    return this.pageMap;
  }

  long getStreamId() {
    return this.streamId;
  }

  long getFirstFrameId() {
    return this.firstFrameId;
  }

  double getMaximumFrameRate() {
    return this.maximumFrameRate;
  }

  Settings getSettings() {
    return this.settings;
  }

  NamedTextColor getOutlineColor() {
    return this.outlineColor;
  }

  long getBacklogLimit() {
    return this.backlogLimit;
  }

  @Nullable Pool getEncoderPool() {
    return this.encoderPool;
  }

  int getUnsentLimit() {
    return this.unsentLimit;
  }
}
