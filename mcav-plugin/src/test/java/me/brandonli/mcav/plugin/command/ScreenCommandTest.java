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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.utils.MapUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class ScreenCommandTest {

  private static final String SCREEN_SIZE = "5x3";
  private static final int SCREEN_WIDTH = 5;
  private static final int SCREEN_HEIGHT = 3;

  private static final int MAP_ID = 10;

  private static final int MAP_ID_HEADROOM = 20;

  private final ScreenCommand command = new ScreenCommand();
  private final CommandSender sender = mock(CommandSender.class);
  private final Location location = new Location(null, 1.0, 64.0, 2.0);

  @Test
  void shouldBuildTheScreenWithTheParsedSize() {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, SCREEN_SIZE, MAP_ID, Material.STONE, this.location);
      maps.verify(() -> MapUtils.buildMapScreen(this.sender, this.location, Material.STONE, SCREEN_WIDTH, SCREEN_HEIGHT, MAP_ID));
    }
    final List<Component> messages = Components.received(this.sender);
    final Component built = Message.SCREEN_BUILD.build();
    final List<Component> expected = List.of(built);
    assertEquals(expected, messages);
  }

  @Test
  void shouldRefuseInvalidSizes() {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, "5by3", MAP_ID, Material.STONE, this.location);
      maps.verifyNoInteractions();
    }
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @ParameterizedTest
  @ValueSource(strings = { "100000x100000", "65x1", "1x65", "8193x8193" })
  void shouldRefuseWallsLargerThanTheLimit(final String size) {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, size, MAP_ID, Material.STONE, this.location);
      maps.verifyNoInteractions();
    }
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @Test
  void shouldBuildTheLargestWall() {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, "64x64", MAP_ID, Material.STONE, this.location);
      maps.verify(() -> MapUtils.buildMapScreen(this.sender, this.location, Material.STONE, 64, 64, MAP_ID));
    }
    final List<Component> messages = Components.received(this.sender);
    final Component built = Message.SCREEN_BUILD.build();
    final List<Component> expected = List.of(built);
    assertEquals(expected, messages);
  }

  @ParameterizedTest
  @ValueSource(classes = { IllegalArgumentException.class, IllegalStateException.class })
  void shouldTellTheSenderWhenTheMapIdsCannotBeUsed(final Class<? extends RuntimeException> failureType) {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      maps.when(this::buildMapScreenNearTheLargestMapId).thenThrow(failureType);
      this.command.buildScreen(this.sender, SCREEN_SIZE, Integer.MAX_VALUE - MAP_ID_HEADROOM, Material.STONE, this.location);
    }
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_MAP_ID.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages, "the sender is told instead of seeing an internal error");
  }

  private void buildMapScreenNearTheLargestMapId() {
    MapUtils.buildMapScreen(this.sender, this.location, Material.STONE, SCREEN_WIDTH, SCREEN_HEIGHT, Integer.MAX_VALUE - MAP_ID_HEADROOM);
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(null, SCREEN_SIZE, MAP_ID, Material.STONE, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, null, MAP_ID, Material.STONE, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, SCREEN_SIZE, MAP_ID, null, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, SCREEN_SIZE, MAP_ID, Material.STONE, null));
  }
}
