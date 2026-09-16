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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MapDisplaySettings}.
 */
final class MapDisplaySettingsTest {

  private static MapConfiguration createSingleMapConfiguration() {
    final Pair<Integer, Integer> size = Pair.pair(1, 1);
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    return MapDisplaySettings.createConfiguration(size, size, 0, viewers);
  }

  @Test
  void keepsTheConfigurationAndSharesStatelessAlgorithms() {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    builder.viewers(viewers);
    builder.map(3);
    builder.mapBlockWidth(2);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(256);
    builder.mapHeightResolution(128);
    final MapConfiguration configuration = builder.build();

    final MapDisplaySettings settings = new MapDisplaySettings(configuration, DitheringArgument.NEAREST_COLOR);
    final MapConfiguration kept = settings.getConfiguration();
    final DitherAlgorithm algorithm = settings.createAlgorithm();
    final DitherAlgorithm expected = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    assertSame(configuration, kept);
    assertSame(expected, algorithm);
  }

  @Test
  void createsAFreshTemporalAlgorithmOnEveryCall() {
    final MapConfiguration configuration = createSingleMapConfiguration();
    final MapDisplaySettings settings = new MapDisplaySettings(configuration, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final DitherAlgorithm first = settings.createAlgorithm();
    final DitherAlgorithm second = settings.createAlgorithm();
    assertNotSame(first, second);
  }

  @Test
  void createsTheConfigurationOfAWallOfMaps() {
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    final Pair<Integer, Integer> blocks = Pair.pair(4, 3);
    final Pair<Integer, Integer> resolution = Pair.pair(512, 384);
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, 9, viewers);

    final int map = configuration.getMap();
    final int blockWidth = configuration.getMapBlockWidth();
    final int blockHeight = configuration.getMapBlockHeight();
    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    final Collection<UUID> configuredViewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(configuredViewers);
    assertEquals(9, map);
    assertEquals(4, blockWidth);
    assertEquals(3, blockHeight);
    assertEquals(512, width);
    assertEquals(384, height);
    assertEquals(viewers, viewerList);
  }

  @Test
  void refusesNullArguments() {
    final Pair<Integer, Integer> size = Pair.pair(1, 1);
    final List<UUID> viewers = List.of();
    final MapConfiguration configuration = createSingleMapConfiguration();
    assertThrows(NullPointerException.class, () -> new MapDisplaySettings(null, DitheringArgument.NEAREST_COLOR));
    assertThrows(NullPointerException.class, () -> new MapDisplaySettings(configuration, null));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(null, size, 0, viewers));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(size, null, 0, viewers));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(size, size, 0, null));
  }
}
