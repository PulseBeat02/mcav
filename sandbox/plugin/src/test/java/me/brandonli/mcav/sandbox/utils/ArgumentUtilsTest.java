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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link ArgumentUtils}.
 */
final class ArgumentUtilsTest {

  @Test
  void parsesWidthAndHeight() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions("16x9");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    assertEquals(16, width);
    assertEquals(9, height);
  }

  @Test
  void acceptsTheSmallestDimensions() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions("1x1");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    assertEquals(1, width);
    assertEquals(1, height);
  }

  @Test
  void rejectsTextWithoutSeparator() {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions("128"));
    final String message = exception.getMessage();
    assertEquals("Invalid dimensions format: 128", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "0x5", "5x0", "-1x5", "5x-1", "0x0" })
  void rejectsSidesThatAreNotPositive(final String text) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(text));
    final String message = exception.getMessage();
    assertEquals("Dimensions must be positive integers: " + text, message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "ax5", "5xb", "4x4x4", "x5", "5x", "", "16X9" })
  void rejectsSidesThatAreNotNumbers(final String text) {
    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(text));
  }

  @Test
  void collectsTheIdsOfTheSelectedPlayers() {
    final UUID firstId = UUID.randomUUID();
    final UUID secondId = UUID.randomUUID();
    final Player first = mock(Player.class);
    final Player second = mock(Player.class);
    when(first.getUniqueId()).thenReturn(firstId);
    when(second.getUniqueId()).thenReturn(secondId);
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(first, second);
    when(selector.values()).thenReturn(players);
    final Collection<UUID> ids = ArgumentUtils.parsePlayerSelectors(selector);
    final List<UUID> expected = List.of(firstId, secondId);
    assertEquals(expected, ids);
  }

  @Test
  void collectsNothingForAnEmptySelection() {
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of();
    when(selector.values()).thenReturn(players);
    final Collection<UUID> ids = ArgumentUtils.parsePlayerSelectors(selector);
    final boolean empty = ids.isEmpty();
    assertTrue(empty);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> ArgumentUtils.parseDimensions(null));
    assertThrows(NullPointerException.class, () -> ArgumentUtils.parsePlayerSelectors(null));
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(ArgumentUtils.class);
  }
}
