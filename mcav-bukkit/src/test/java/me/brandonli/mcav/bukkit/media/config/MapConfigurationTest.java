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

/**
 * Tests {@link MapConfiguration}.
 */
final class MapConfigurationTest {

  private static MapConfiguration.Builder<?> completeBuilder() {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(List.of());
    builder.map(0);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    return builder;
  }

  private static Object configureResize(final MapConfiguration.Builder<?> builder, final boolean resize) {
    return builder.resize(resize);
  }

  @Test
  void defaultsTheResolutionToTheNativeResolutionOfTheScreen() {
    final List<UUID> viewers = new ArrayList<>();
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(viewers);
    builder.map(12);
    builder.mapBlockWidth(5);
    builder.mapBlockHeight(3);

    final MapConfiguration configuration = builder.build();

    final Object configuredViewers = configuration.getViewers();
    final int map = configuration.getMap();
    final int columns = configuration.getMapBlockWidth();
    final int rows = configuration.getMapBlockHeight();
    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    final boolean resize = configuration.shouldResize();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertEquals(12, map);
    assertEquals(5, columns);
    assertEquals(3, rows);
    assertEquals(640, width);
    assertEquals(384, height);
    assertFalse(resize);
  }

  @Test
  void keepsAnExplicitResolutionAndTheResizeFlag() {
    final MapConfiguration.Builder<?> builder = completeBuilder();
    builder.mapWidthResolution(100);
    builder.mapHeightResolution(50);
    configureResize(builder, true);

    final MapConfiguration configuration = builder.build();

    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    final boolean resize = configuration.shouldResize();
    assertEquals(100, width);
    assertEquals(50, height);
    assertTrue(resize);
  }

  @Test
  void settersReturnTheSameBuilder() {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterMap = builder.map(0);
    final Object afterWidth = builder.mapBlockWidth(1);
    final Object afterHeight = builder.mapBlockHeight(1);
    final Object afterWidthResolution = builder.mapWidthResolution(1);
    final Object afterHeightResolution = builder.mapHeightResolution(1);
    final Object afterResize = configureResize(builder, false);

    assertInstanceOf(MapConfiguration.MapResultBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterMap);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
    assertSame(builder, afterWidthResolution);
    assertSame(builder, afterHeightResolution);
    assertSame(builder, afterResize);
  }

  @Test
  void settersRejectNull() {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();

    assertThrows(NullPointerException.class, () -> builder.viewers(null));
  }

  @Test
  void rejectsMissingOrInvalidValues() {
    final MapConfiguration.Builder<?> noViewers = MapConfiguration.builder();
    noViewers.map(0);
    noViewers.mapBlockWidth(1);
    noViewers.mapBlockHeight(1);
    final MapConfiguration.Builder<?> noMap = MapConfiguration.builder();
    noMap.viewers(List.of());
    noMap.mapBlockWidth(1);
    noMap.mapBlockHeight(1);
    final MapConfiguration.Builder<?> negativeMap = completeBuilder();
    negativeMap.map(-2);
    final MapConfiguration.Builder<?> noWidth = completeBuilder();
    noWidth.mapBlockWidth(0);
    final MapConfiguration.Builder<?> noHeight = completeBuilder();
    noHeight.mapBlockHeight(0);

    assertThrows(NullPointerException.class, noViewers::build);
    assertThrows(IllegalArgumentException.class, noMap::build);
    assertThrows(IllegalArgumentException.class, negativeMap::build);
    assertThrows(IllegalArgumentException.class, noWidth::build);
    assertThrows(IllegalArgumentException.class, noHeight::build);
  }

  @Test
  void rejectsNegativeResolutions() {
    final MapConfiguration.Builder<?> negativeWidthResolution = completeBuilder();
    negativeWidthResolution.mapWidthResolution(-1);
    final MapConfiguration.Builder<?> negativeHeightResolution = completeBuilder();
    negativeHeightResolution.mapHeightResolution(-1);

    final IllegalArgumentException widthException = assertThrows(IllegalArgumentException.class, negativeWidthResolution::build);
    final IllegalArgumentException heightException = assertThrows(IllegalArgumentException.class, negativeHeightResolution::build);

    final String widthMessage = widthException.getMessage();
    final String heightMessage = heightException.getMessage();
    assertEquals("Map width resolution must not be negative", widthMessage);
    assertEquals("Map height resolution must not be negative", heightMessage);
  }

  @Test
  void reusingTheBuilderRecomputesTheDefaultResolution() {
    final MapConfiguration.Builder<?> builder = completeBuilder();

    final MapConfiguration small = builder.build();
    builder.mapBlockWidth(3);
    builder.mapBlockHeight(2);
    final MapConfiguration large = builder.build();

    final int smallWidth = small.getMapWidthResolution();
    final int smallHeight = small.getMapHeightResolution();
    final int largeWidth = large.getMapWidthResolution();
    final int largeHeight = large.getMapHeightResolution();
    assertEquals(128, smallWidth);
    assertEquals(128, smallHeight);
    assertEquals(384, largeWidth, "the default of the first build is not kept in the builder");
    assertEquals(256, largeHeight);
  }
}
