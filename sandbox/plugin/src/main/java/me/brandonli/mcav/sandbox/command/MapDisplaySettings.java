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
package me.brandonli.mcav.sandbox.command;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;

/**
 * The settings of a map display: which maps show the media and how colors are dithered onto them.
 */
public final class MapDisplaySettings {

  private final MapConfiguration configuration;
  private final DitheringArgument dithering;

  /**
   * Constructs the settings.
   *
   * @param configuration the maps that show the media
   * @param dithering     the dithering algorithm
   */
  public MapDisplaySettings(final MapConfiguration configuration, final DitheringArgument dithering) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(dithering, "Dithering must not be null");
    this.configuration = configuration;
    this.dithering = dithering;
  }

  /**
   * Creates the configuration of a wall of maps, as used by the map commands and the interactive screens.
   *
   * @param blocks     the size of the wall in maps
   * @param resolution the resolution the media is scaled to
   * @param mapId      the id of the top-left map
   * @param players    the players who see the maps
   * @return the configuration
   */
  public static MapConfiguration createConfiguration(
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Collection<UUID> players
  ) {
    Preconditions.checkNotNull(blocks, "Blocks must not be null");
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(players, "Players must not be null");
    final int blockWidth = blocks.getFirst();
    final int blockHeight = blocks.getSecond();
    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(players);
    builder.map(mapId);
    builder.mapBlockWidth(blockWidth);
    builder.mapBlockHeight(blockHeight);
    builder.mapWidthResolution(resolutionWidth);
    builder.mapHeightResolution(resolutionHeight);
    return builder.build();
  }

  /**
   * Gets the maps that show the media.
   *
   * @return the map configuration
   */
  public MapConfiguration getConfiguration() {
    return this.configuration;
  }

  /**
   * Creates the dithering algorithm that colors are reduced to the map palette with. Stateful dithering, such as
   * {@link DitheringArgument#FLOYD_STEINBERG_TEMPORAL}, remembers the previous frame, so each call gives a fresh
   * algorithm for it: call this once for every player or image and keep the result. Stateless algorithms are shared.
   *
   * @return the algorithm
   */
  public DitherAlgorithm createAlgorithm() {
    return this.dithering.createAlgorithm();
  }
}
