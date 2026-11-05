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

final class DefaultEntityConfigurationBuilder implements EntityConfigurationBuilder {

  private @MonotonicNonNull Collection<UUID> viewers;
  private @MonotonicNonNull String character;
  private int entityWidth;
  private int entityHeight;
  private @MonotonicNonNull Location position;

  DefaultEntityConfigurationBuilder() {}

  @Override
  public EntityConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public EntityConfigurationBuilder character(final String character) {
    Preconditions.checkNotNull(character, "Character must not be null!");

    this.character = character;
    return this;
  }

  @Override
  public EntityConfigurationBuilder entityWidth(final int entityWidth) {
    this.entityWidth = entityWidth;
    return this;
  }

  @Override
  public EntityConfigurationBuilder entityHeight(final int entityHeight) {
    this.entityHeight = entityHeight;
    return this;
  }

  @Override
  public EntityConfigurationBuilder position(final Location position) {
    Preconditions.checkNotNull(position, "Position must not be null!");

    this.position = position;
    return this;
  }

  @Override
  public EntityConfiguration build() {
    final @Nullable Collection<UUID> configuredViewers = this.viewers;
    if (configuredViewers == null) {
      final String message = "Viewers must be set!";
      throw new IllegalStateException(message);
    }

    final @Nullable String configuredCharacter = this.character;
    if (configuredCharacter == null) {
      final String message = "Character must be set!";
      throw new IllegalStateException(message);
    }

    final @Nullable Location configuredPosition = this.position;
    if (configuredPosition == null) {
      final String message = "Position must be set!";
      throw new IllegalStateException(message);
    }

    final World world = configuredPosition.getWorld();
    Preconditions.checkArgument(world != null, "Position must have a world!");
    Preconditions.checkArgument(!configuredCharacter.isEmpty(), "Character must not be empty!");
    Preconditions.checkArgument(this.entityWidth > 0, "Entity width %s must be positive!", this.entityWidth);
    Preconditions.checkArgument(this.entityHeight > 0, "Entity height %s must be positive!", this.entityHeight);

    return new EntityConfiguration(configuredViewers, configuredCharacter, this.entityWidth, this.entityHeight, configuredPosition);
  }
}
