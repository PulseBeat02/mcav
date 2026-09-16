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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.utils.MapUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link ScreenCommand}.
 */
final class ScreenCommandTest {

  private final ScreenCommand command = new ScreenCommand();
  private final CommandSender sender = mock(CommandSender.class);
  private final Location location = new Location(null, 1.0, 64.0, 2.0);

  @Test
  void buildsTheScreenWithTheParsedSize() {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, "5x3", 10, Material.STONE, this.location);
      maps.verify(() -> MapUtils.buildMapScreen(this.sender, this.location, Material.STONE, 5, 3, 10));
    }
    final List<Component> messages = Components.received(this.sender);
    final Component built = Message.SCREEN_BUILD.build();
    final List<Component> expected = List.of(built);
    assertEquals(expected, messages);
  }

  @Test
  void refusesInvalidSizes() {
    try (final MockedStatic<MapUtils> maps = Mockito.mockStatic(MapUtils.class)) {
      this.command.buildScreen(this.sender, "5by3", 10, Material.STONE, this.location);
      maps.verifyNoInteractions();
    }
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(null, "5x3", 10, Material.STONE, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, null, 10, Material.STONE, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, "5x3", 10, null, this.location));
    assertThrows(NullPointerException.class, () -> this.command.buildScreen(this.sender, "5x3", 10, Material.STONE, null));
  }
}
