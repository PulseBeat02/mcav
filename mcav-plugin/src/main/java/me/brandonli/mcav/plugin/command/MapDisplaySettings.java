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
package me.brandonli.mcav.plugin.command;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The settings of a map display: which maps show the media and how colors are dithered onto them.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class MapDisplaySettings {

  private final MapConfiguration configuration;
  private final DitheringArgument dithering;

  private MapDisplaySettings(final MapConfiguration configuration, final DitheringArgument dithering) {
    this.configuration = configuration;
    this.dithering = dithering;
  }

  /**
   * Creates the settings.
   *
   * @param configuration the maps that show the media
   * @param dithering     the dithering algorithm
   * @return the settings
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static MapDisplaySettings of(final MapConfiguration configuration, final DitheringArgument dithering) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");
    Preconditions.checkNotNull(dithering, "Dithering must not be null!");

    return new MapDisplaySettings(configuration, dithering);
  }

  /**
   * Creates the configuration of a wall of maps, as used by the map commands and the interactive screens.
   *
   * @param blocks     the size of the wall in maps
   * @param resolution the resolution the media is scaled to
   * @param mapId      the id of the top-left map
   * @param players    the players who see the maps
   * @return the configuration
   * @throws NullPointerException     if an argument is null
   * @throws IllegalArgumentException if {@code mapId} is negative, a side of {@code blocks} is not positive, a side of
   *                                  {@code resolution} is negative, or a default resolution would overflow an integer
   * @since 1.0.0
   */
  public static MapConfiguration createConfiguration(
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Collection<UUID> players
  ) {
    Preconditions.checkNotNull(blocks, "Blocks must not be null!");
    Preconditions.checkNotNull(resolution, "Resolution must not be null!");
    Preconditions.checkNotNull(players, "Players must not be null!");

    final int blockWidth = blocks.getFirst();
    final int blockHeight = blocks.getSecond();
    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(players);
    builder.map(mapId);
    builder.mapBlockWidth(blockWidth);
    builder.mapBlockHeight(blockHeight);
    builder.mapWidthResolution(resolutionWidth);
    builder.mapHeightResolution(resolutionHeight);
    return builder.build();
  }

  /**
   * Creates the dithering algorithm that colors are reduced to the map palette with. Stateful dithering, such as
   * {@link DitheringArgument#FLOYD_STEINBERG_TEMPORAL}, remembers the previous frame, so each call gives a fresh
   * algorithm for it: call this once for every player or image and keep the result. Stateless algorithms are shared.
   *
   * @return the algorithm
   * @since 1.0.0
   */
  public DitherAlgorithm createAlgorithm() {
    return this.dithering.createAlgorithm();
  }

  /**
   * Returns the maps that show the media.
   *
   * @return the map configuration
   * @since 1.0.0
   */
  public MapConfiguration getConfiguration() {
    return this.configuration;
  }

  /**
   * Returns how colors are dithered onto the maps.
   *
   * @return the dithering algorithm
   * @since 1.0.0
   */
  public DitheringArgument getDithering() {
    return this.dithering;
  }

  /**
   * Returns whether {@code other} is settings with the same configuration and dithering. A {@link MapConfiguration}
   * equals only itself, so the configuration must be the same instance.
   *
   * @param other the object to compare with
   * @return whether both match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final MapDisplaySettings settings)) {
      return false;
    }

    return this.configuration.equals(settings.configuration) && this.dithering == settings.dithering;
  }

  /**
   * Returns a hash code built from the configuration and the dithering.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.configuration, this.dithering);
  }

  /**
   * Returns a debugging representation with the configuration and the dithering.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("configuration", this.configuration).add("dithering", this.dithering).toString();
  }
}
