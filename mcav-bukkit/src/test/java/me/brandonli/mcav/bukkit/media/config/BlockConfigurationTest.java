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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class BlockConfigurationTest {

  // a location refers to its world only weakly, so the test keeps the world itself
  private static final World WORLD = mock(World.class);
  private static final Location POSITION = new Location(WORLD, 1, 2, 3);

  @Test
  void shouldRejectPositionsWithoutAWorld() {
    final Location nowhere = new Location(null, 1, 2, 3);
    final BlockConfigurationBuilder builder = BlockConfiguration.builder();
    builder.viewers(List.of());
    builder.position(nowhere);
    builder.blockWidth(1);
    builder.blockHeight(1);

    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, builder::build);

    final String message = exception.getMessage();
    assertEquals("Position must have a world!", message);
  }

  @Test
  void shouldBuildAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final BlockConfigurationBuilder builder = BlockConfiguration.builder();
    builder.viewers(viewers);
    builder.position(POSITION);
    builder.blockWidth(4);
    builder.blockHeight(5);

    final BlockConfiguration configuration = builder.build();
    final UUID lateViewer = UUID.randomUUID();
    viewers.add(lateViewer);

    final Object configuredViewers = configuration.getViewers();
    final Location position = configuration.getPosition();
    final int width = configuration.getBlockWidth();
    final int height = configuration.getBlockHeight();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertSame(POSITION, position);
    assertEquals(4, width);
    assertEquals(5, height);
  }

  @Test
  void shouldReturnTheSameBuilderFromSetters() {
    final BlockConfigurationBuilder builder = BlockConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterPosition = builder.position(POSITION);
    final Object afterWidth = builder.blockWidth(1);
    final Object afterHeight = builder.blockHeight(1);

    assertInstanceOf(DefaultBlockConfigurationBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterPosition);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
  }

  @Test
  void shouldRejectNullInSetters() {
    final BlockConfigurationBuilder builder = BlockConfiguration.builder();

    final NullPointerException nullViewersException = assertThrows(NullPointerException.class, () -> builder.viewers(null));
    final NullPointerException nullPositionException = assertThrows(NullPointerException.class, () -> builder.position(null));
    final String nullViewersMessage = nullViewersException.getMessage();
    final String nullPositionMessage = nullPositionException.getMessage();
    assertEquals("Viewers must not be null!", nullViewersMessage);
    assertEquals("Position must not be null!", nullPositionMessage);
  }

  @Test
  void shouldRejectMissingOrInvalidValues() {
    final BlockConfigurationBuilder noViewers = BlockConfiguration.builder();
    noViewers.position(POSITION);
    noViewers.blockWidth(1);
    noViewers.blockHeight(1);
    final BlockConfigurationBuilder noPosition = BlockConfiguration.builder();
    noPosition.viewers(List.of());
    noPosition.blockWidth(1);
    noPosition.blockHeight(1);
    final BlockConfigurationBuilder noWidth = BlockConfiguration.builder();
    noWidth.viewers(List.of());
    noWidth.position(POSITION);
    noWidth.blockHeight(1);
    final BlockConfigurationBuilder noHeight = BlockConfiguration.builder();
    noHeight.viewers(List.of());
    noHeight.position(POSITION);
    noHeight.blockWidth(1);

    final IllegalStateException noViewersException = assertThrows(IllegalStateException.class, noViewers::build);
    final IllegalStateException noPositionException = assertThrows(IllegalStateException.class, noPosition::build);
    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, noWidth::build);
    final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, noHeight::build);
    final String noViewersMessage = noViewersException.getMessage();
    final String noPositionMessage = noPositionException.getMessage();
    final String noWidthMessage = noWidthException.getMessage();
    final String noHeightMessage = noHeightException.getMessage();
    assertEquals("Viewers must be set!", noViewersMessage);
    assertEquals("Position must be set!", noPositionMessage);
    assertEquals("Block width 0 must be positive!", noWidthMessage);
    assertEquals("Block height 0 must be positive!", noHeightMessage);
  }
}
