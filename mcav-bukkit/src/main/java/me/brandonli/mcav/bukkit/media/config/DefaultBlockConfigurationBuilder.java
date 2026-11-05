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
import org.bukkit.Location;
import org.bukkit.World;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultBlockConfigurationBuilder implements BlockConfigurationBuilder {

  private @MonotonicNonNull Collection<UUID> viewers;
  private int blockWidth;
  private int blockHeight;
  private @MonotonicNonNull Location position;

  DefaultBlockConfigurationBuilder() {}

  @Override
  public BlockConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public BlockConfigurationBuilder position(final Location position) {
    Preconditions.checkNotNull(position, "Position must not be null!");

    this.position = position;
    return this;
  }

  @Override
  public BlockConfigurationBuilder blockWidth(final int blockWidth) {
    this.blockWidth = blockWidth;
    return this;
  }

  @Override
  public BlockConfigurationBuilder blockHeight(final int blockHeight) {
    this.blockHeight = blockHeight;
    return this;
  }

  @Override
  public BlockConfiguration build() {
    final @Nullable Collection<UUID> configuredViewers = this.viewers;
    if (configuredViewers == null) {
      final String message = "Viewers must be set!";
      throw new IllegalStateException(message);
    }

    final @Nullable Location configuredPosition = this.position;
    if (configuredPosition == null) {
      final String message = "Position must be set!";
      throw new IllegalStateException(message);
    }

    final World world = configuredPosition.getWorld();
    Preconditions.checkArgument(world != null, "Position must have a world!");
    Preconditions.checkArgument(this.blockWidth > 0, "Block width %s must be positive!", this.blockWidth);
    Preconditions.checkArgument(this.blockHeight > 0, "Block height %s must be positive!", this.blockHeight);

    return new BlockConfiguration(configuredViewers, this.blockWidth, this.blockHeight, configuredPosition);
  }
}
