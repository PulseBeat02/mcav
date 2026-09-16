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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link ScoreboardArguments}.
 */
final class ScoreboardArgumentsTest {

  private CommandSender sender;

  @BeforeEach
  void createSender() {
    this.sender = mock(CommandSender.class);
  }

  @ParameterizedTest
  @ValueSource(strings = { "24x1", "24x15", "1x15" })
  void acceptsHeightsUpToTheLineLimit(final String resolution) {
    final boolean fits = ScoreboardArguments.checkLines(this.sender, resolution);
    assertTrue(fits);
    verify(this.sender, never()).sendMessage(any(Component.class));
  }

  @ParameterizedTest
  @ValueSource(strings = { "24x16", "8x100" })
  void refusesHeightsAboveTheLineLimitAndTellsTheSender(final String resolution) {
    final boolean fits = ScoreboardArguments.checkLines(this.sender, resolution);
    assertFalse(fits);
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.SCOREBOARD_LINES.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @ParameterizedTest
  @ValueSource(strings = { "wide", "24x", "0x5" })
  void leavesResolutionsThatCannotBeParsedToTheCommand(final String resolution) {
    final boolean fits = ScoreboardArguments.checkLines(this.sender, resolution);
    assertTrue(fits);
    verify(this.sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void usesTheLineLimitOfTheLibrary() {
    final int limit = ScoreboardConfiguration.MAX_LINES;
    final boolean atLimit = ScoreboardArguments.checkLines(this.sender, "24x" + limit);
    final int above = limit + 1;
    final boolean aboveLimit = ScoreboardArguments.checkLines(this.sender, "24x" + above);
    assertTrue(atLimit);
    assertFalse(aboveLimit);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> ScoreboardArguments.checkLines(null, "24x15"));
    assertThrows(NullPointerException.class, () -> ScoreboardArguments.checkLines(this.sender, null));
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(ScoreboardArguments.class);
  }
}
