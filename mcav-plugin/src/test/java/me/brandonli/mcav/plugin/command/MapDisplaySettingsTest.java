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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.MoreObjects;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.junit.jupiter.api.Test;

final class MapDisplaySettingsTest {

  @Test
  void shouldKeepTheConfigurationAndShareStatelessAlgorithms() {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    builder.viewers(viewers);
    builder.map(3);
    builder.mapBlockWidth(2);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(256);
    builder.mapHeightResolution(128);
    final MapConfiguration configuration = builder.build();

    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.NEAREST_COLOR);
    final MapConfiguration kept = settings.getConfiguration();
    final DitherAlgorithm algorithm = settings.createAlgorithm();
    final DitherAlgorithm expected = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    assertSame(configuration, kept);
    assertSame(expected, algorithm);
  }

  @Test
  void shouldCreateAFreshTemporalAlgorithmOnEveryCall() {
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final DitherAlgorithm first = settings.createAlgorithm();
    final DitherAlgorithm second = settings.createAlgorithm();
    assertNotSame(first, second);
  }

  private MapConfiguration createSingleMapConfiguration() {
    final Pair<Integer, Integer> size = Pair.pair(1, 1);
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    return MapDisplaySettings.createConfiguration(size, size, 0, viewers);
  }

  @Test
  void shouldCreateTheConfigurationOfAWallOfMaps() {
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
  void shouldRefuseNullArguments() {
    final Pair<Integer, Integer> size = Pair.pair(1, 1);
    final List<UUID> viewers = List.of();
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.of(null, DitheringArgument.NEAREST_COLOR));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.of(configuration, null));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(null, size, 0, viewers));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(size, null, 0, viewers));
    assertThrows(NullPointerException.class, () -> MapDisplaySettings.createConfiguration(size, size, 0, null));
  }

  @Test
  void shouldRefuseANegativeMapIdAnEmptySideOrANegativeResolution() {
    final Pair<Integer, Integer> size = Pair.pair(1, 1);
    final Pair<Integer, Integer> noWidth = Pair.pair(0, 1);
    final Pair<Integer, Integer> noHeight = Pair.pair(1, 0);
    final Pair<Integer, Integer> negativeWidth = Pair.pair(-1, 128);
    final UUID viewer = UUID.randomUUID();
    final List<UUID> viewers = List.of(viewer);
    assertThrows(IllegalArgumentException.class, () -> MapDisplaySettings.createConfiguration(size, size, -1, viewers));
    assertThrows(IllegalArgumentException.class, () -> MapDisplaySettings.createConfiguration(noWidth, size, 0, viewers));
    assertThrows(IllegalArgumentException.class, () -> MapDisplaySettings.createConfiguration(noHeight, size, 0, viewers));
    assertThrows(IllegalArgumentException.class, () -> MapDisplaySettings.createConfiguration(size, negativeWidth, 0, viewers));
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.NEAREST_COLOR);
    final MapDisplaySettings equalSettings = MapDisplaySettings.of(configuration, DitheringArgument.NEAREST_COLOR);
    final MapConfiguration otherConfiguration = this.createSingleMapConfiguration();
    final MapDisplaySettings otherConfigurationSettings = MapDisplaySettings.of(otherConfiguration, DitheringArgument.NEAREST_COLOR);
    final MapDisplaySettings otherDithering = MapDisplaySettings.of(configuration, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    EqualityAssertions.assertEqualityContract(settings, equalSettings, otherConfigurationSettings, otherDithering);
  }

  @Test
  void shouldHashTheConfigurationThenTheDithering() {
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.NEAREST_COLOR);

    final int hash = settings.hashCode();
    final int expected = Objects.hash(configuration, DitheringArgument.NEAREST_COLOR);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderTheConfigurationAndTheDithering() {
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.NEAREST_COLOR);

    final String text = settings.toString();
    final String expected = MoreObjects.toStringHelper("MapDisplaySettings")
      .add("configuration", configuration)
      .add("dithering", DitheringArgument.NEAREST_COLOR)
      .toString();
    assertEquals(expected, text);
  }

  @Test
  void shouldKeepTheDithering() {
    final MapConfiguration configuration = this.createSingleMapConfiguration();
    final MapDisplaySettings settings = MapDisplaySettings.of(configuration, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final DitheringArgument dithering = settings.getDithering();
    assertEquals(DitheringArgument.FLOYD_STEINBERG_TEMPORAL, dithering);
  }
}
