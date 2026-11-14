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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MapConfigurationTest {

  private static final int MAP_BLOCK_COUNT = 3;

  private static final int NATIVE_RESOLUTION = 384;

  private static final int MAP_SIZE = 128;

  private MapConfigurationBuilder completeBuilder() {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(List.of());
    builder.map(0);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    return builder;
  }

  @Test
  void shouldDefaultTheResolutionToTheNativeResolutionOfTheScreen() {
    final List<UUID> viewers = new ArrayList<>();
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(viewers);
    builder.map(12);
    builder.mapBlockWidth(5);
    builder.mapBlockHeight(MAP_BLOCK_COUNT);

    final MapConfiguration configuration = builder.build();

    final Object configuredViewers = configuration.getViewers();
    final int map = configuration.getMap();
    final int columns = configuration.getMapBlockWidth();
    final int rows = configuration.getMapBlockHeight();
    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    final boolean shouldResize = configuration.shouldResize();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertEquals(12, map);
    assertEquals(5, columns);
    assertEquals(MAP_BLOCK_COUNT, rows);
    assertEquals(640, width);
    assertEquals(NATIVE_RESOLUTION, height);
    assertFalse(shouldResize);
  }

  @Test
  void shouldKeepAnExplicitResolutionAndTheResizeFlag() {
    final MapConfigurationBuilder builder = this.completeBuilder();
    builder.mapWidthResolution(100);
    builder.mapHeightResolution(50);
    builder.resize(true);

    final MapConfiguration configuration = builder.build();

    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    final boolean shouldResize = configuration.shouldResize();
    assertEquals(100, width);
    assertEquals(50, height);
    assertTrue(shouldResize);
  }

  @Test
  void shouldReturnTheSameBuilderFromSetters() {
    final MapConfigurationBuilder builder = MapConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterMap = builder.map(0);
    final Object afterWidth = builder.mapBlockWidth(1);
    final Object afterHeight = builder.mapBlockHeight(1);
    final Object afterWidthResolution = builder.mapWidthResolution(1);
    final Object afterHeightResolution = builder.mapHeightResolution(1);
    final Object afterResize = builder.resize(false);

    assertInstanceOf(DefaultMapConfigurationBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterMap);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
    assertSame(builder, afterWidthResolution);
    assertSame(builder, afterHeightResolution);
    assertSame(builder, afterResize);
  }

  @Test
  void shouldRejectNullInSetters() {
    final MapConfigurationBuilder builder = MapConfiguration.builder();

    final NullPointerException nullViewersException = assertThrows(NullPointerException.class, () -> builder.viewers(null));
    final String nullViewersMessage = nullViewersException.getMessage();
    assertEquals("Viewers must not be null!", nullViewersMessage);
  }

  @Test
  void shouldRejectMissingOrInvalidValues() {
    final MapConfigurationBuilder noViewers = MapConfiguration.builder();
    noViewers.map(0);
    noViewers.mapBlockWidth(1);
    noViewers.mapBlockHeight(1);
    final MapConfigurationBuilder noMap = MapConfiguration.builder();
    noMap.viewers(List.of());
    noMap.mapBlockWidth(1);
    noMap.mapBlockHeight(1);
    final MapConfigurationBuilder negativeMap = this.completeBuilder();
    negativeMap.map(-2);
    final MapConfigurationBuilder noWidth = this.completeBuilder();
    noWidth.mapBlockWidth(0);
    final MapConfigurationBuilder noHeight = this.completeBuilder();
    noHeight.mapBlockHeight(0);

    final IllegalStateException noViewersException = assertThrows(IllegalStateException.class, noViewers::build);
    final IllegalArgumentException noMapException = assertThrows(IllegalArgumentException.class, noMap::build);
    final IllegalArgumentException negativeMapException = assertThrows(IllegalArgumentException.class, negativeMap::build);
    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, noWidth::build);
    final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, noHeight::build);
    final String noViewersMessage = noViewersException.getMessage();
    final String noMapMessage = noMapException.getMessage();
    final String negativeMapMessage = negativeMapException.getMessage();
    final String noWidthMessage = noWidthException.getMessage();
    final String noHeightMessage = noHeightException.getMessage();
    assertEquals("Viewers must be set!", noViewersMessage);
    assertEquals("Map id -1 must be set and must not be negative!", noMapMessage);
    assertEquals("Map id -2 must be set and must not be negative!", negativeMapMessage);
    assertEquals("Map block width 0 must be positive!", noWidthMessage);
    assertEquals("Map block height 0 must be positive!", noHeightMessage);
  }

  @Test
  void shouldRejectNegativeResolutions() {
    final MapConfigurationBuilder negativeWidthResolution = this.completeBuilder();
    negativeWidthResolution.mapWidthResolution(-1);
    final MapConfigurationBuilder negativeHeightResolution = this.completeBuilder();
    negativeHeightResolution.mapHeightResolution(-1);

    final IllegalArgumentException widthException = assertThrows(IllegalArgumentException.class, negativeWidthResolution::build);
    final IllegalArgumentException heightException = assertThrows(IllegalArgumentException.class, negativeHeightResolution::build);

    final String widthMessage = widthException.getMessage();
    final String heightMessage = heightException.getMessage();
    assertEquals("Map width resolution -1 must not be negative!", widthMessage);
    assertEquals("Map height resolution -1 must not be negative!", heightMessage);
  }

  @Test
  void shouldRecomputeTheDefaultResolutionWhenReusingTheBuilder() {
    final MapConfigurationBuilder builder = this.completeBuilder();

    final MapConfiguration small = builder.build();
    builder.mapBlockWidth(MAP_BLOCK_COUNT);
    builder.mapBlockHeight(2);
    final MapConfiguration large = builder.build();

    final int smallWidth = small.getMapWidthResolution();
    final int smallHeight = small.getMapHeightResolution();
    final int largeWidth = large.getMapWidthResolution();
    final int largeHeight = large.getMapHeightResolution();
    assertEquals(MAP_SIZE, smallWidth);
    assertEquals(MAP_SIZE, smallHeight);
    assertEquals(NATIVE_RESOLUTION, largeWidth, "the default of the first build is not kept in the builder");
    assertEquals(256, largeHeight);
  }

  @Test
  void shouldRejectOverflowingNativeResolutions() {
    final MapConfigurationBuilder width = this.completeBuilder();
    width.mapBlockWidth(Integer.MAX_VALUE / MAP_SIZE + 1);
    final MapConfigurationBuilder height = this.completeBuilder();
    height.mapBlockHeight(Integer.MAX_VALUE / MAP_SIZE + 1);
    final IllegalArgumentException widthException = assertThrows(IllegalArgumentException.class, width::build);
    final IllegalArgumentException heightException = assertThrows(IllegalArgumentException.class, height::build);
    final String widthMessage = widthException.getMessage();
    final String heightMessage = heightException.getMessage();
    assertEquals("Native map resolution exceeds the integer range!", widthMessage);
    assertEquals("Native map resolution exceeds the integer range!", heightMessage);
  }

  @Test
  void shouldAcceptTheLargestRepresentableNativeResolution() {
    final int maps = Integer.MAX_VALUE / MAP_SIZE;
    final MapConfigurationBuilder builder = this.completeBuilder();
    builder.mapBlockWidth(maps);
    final MapConfiguration configuration = builder.build();
    final int resolution = configuration.getMapWidthResolution();
    final int expected = maps * MAP_SIZE;
    assertEquals(expected, resolution);
  }
}
