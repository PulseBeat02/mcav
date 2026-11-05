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
package me.brandonli.mcav.bukkit.media.config;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultMapConfigurationBuilder implements MapConfigurationBuilder {

  private static final int NO_MAP_ID = -1;

  private @MonotonicNonNull Collection<UUID> viewers;
  private int map;
  private int mapBlockWidth;
  private int mapBlockHeight;
  private int mapWidthResolution;
  private int mapHeightResolution;
  private boolean shouldResize;

  DefaultMapConfigurationBuilder() {
    this.map = NO_MAP_ID;
  }

  @Override
  public MapConfigurationBuilder resize(final boolean shouldResize) {
    this.shouldResize = shouldResize;
    return this;
  }

  @Override
  public MapConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public MapConfigurationBuilder map(final int map) {
    this.map = map;
    return this;
  }

  @Override
  public MapConfigurationBuilder mapBlockWidth(final int mapBlockWidth) {
    this.mapBlockWidth = mapBlockWidth;
    return this;
  }

  @Override
  public MapConfigurationBuilder mapBlockHeight(final int mapBlockHeight) {
    this.mapBlockHeight = mapBlockHeight;
    return this;
  }

  @Override
  public MapConfigurationBuilder mapWidthResolution(final int mapWidthResolution) {
    this.mapWidthResolution = mapWidthResolution;
    return this;
  }

  @Override
  public MapConfigurationBuilder mapHeightResolution(final int mapHeightResolution) {
    this.mapHeightResolution = mapHeightResolution;
    return this;
  }

  @Override
  public MapConfiguration build() {
    final @Nullable Collection<UUID> configuredViewers = this.viewers;
    if (configuredViewers == null) {
      final String message = "Viewers must be set!";
      throw new IllegalStateException(message);
    }

    Preconditions.checkArgument(this.map >= 0, "Map id %s must be set and must not be negative!", this.map);
    Preconditions.checkArgument(this.mapBlockWidth > 0, "Map block width %s must be positive!", this.mapBlockWidth);
    Preconditions.checkArgument(this.mapBlockHeight > 0, "Map block height %s must be positive!", this.mapBlockHeight);
    Preconditions.checkArgument(this.mapWidthResolution >= 0, "Map width resolution %s must not be negative!", this.mapWidthResolution);
    Preconditions.checkArgument(this.mapHeightResolution >= 0, "Map height resolution %s must not be negative!", this.mapHeightResolution);

    final int widthResolution = this.resolveResolution(this.mapWidthResolution, this.mapBlockWidth);
    final int heightResolution = this.resolveResolution(this.mapHeightResolution, this.mapBlockHeight);
    return new MapConfiguration(
      configuredViewers,
      this.map,
      this.mapBlockWidth,
      this.mapBlockHeight,
      widthResolution,
      heightResolution,
      this.shouldResize
    );
  }

  private int resolveResolution(final int configuredResolution, final int maps) {
    if (configuredResolution > 0) {
      return configuredResolution;
    }

    Preconditions.checkArgument(maps <= Integer.MAX_VALUE / MapLayout.MAP_SIZE, "Native map resolution exceeds the integer range!");

    return MapLayout.MAP_SIZE * maps;
  }
}
