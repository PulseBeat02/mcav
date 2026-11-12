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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ArgumentUtilsTest {

  @Test
  void shouldParseWidthAndHeight() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions("16x9");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    assertEquals(16, width);
    assertEquals(9, height);
  }

  @Test
  void shouldAcceptTheSmallestDimensions() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions("1x1");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    assertEquals(1, width);
    assertEquals(1, height);
  }

  @ParameterizedTest
  @ValueSource(strings = { "128", "", "16X9" })
  void shouldRejectTextWithoutALowercaseSeparator(final String text) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(text));

    final String message = exception.getMessage();
    assertEquals("Dimensions %s have an invalid format!".formatted(text), message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "0x5", "5x0", "-1x5", "5x-1", "0x0" })
  void shouldRejectSidesThatAreNotPositive(final String text) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(text));

    final String message = exception.getMessage();
    assertEquals("Dimensions %s must be positive integers!".formatted(text), message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "ax5", "5xb", "4x4x4", "x5", "5x", "5 x 5", "5x5px" })
  void shouldReportSidesThatAreNotWholeNumbersAsNumberFormatErrors(final String text) {
    assertThrows(NumberFormatException.class, () -> ArgumentUtils.parseDimensions(text));
  }

  @Test
  void shouldAcceptTheLargestSides() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions("8192x8192");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    assertEquals(ArgumentUtils.MAXIMUM_SIDE, width);
    assertEquals(ArgumentUtils.MAXIMUM_SIDE, height);
  }

  @ParameterizedTest
  @ValueSource(strings = { "8193x1", "1x8193", "100000x100000", "2147483647x2147483647" })
  void shouldRejectSidesLargerThanTheLimit(final String text) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(text));

    final String message = exception.getMessage();
    assertEquals("Dimensions %s must be at most 8192 on each side!".formatted(text), message);
  }

  @Test
  void shouldAcceptTheLargestWall() {
    final Pair<Integer, Integer> dimensions = ArgumentUtils.parseScreenDimensions("64x64");
    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    final int maps = width * height;
    assertEquals(64, width);
    assertEquals(64, height);
    assertEquals(4096, maps, "the largest wall fits into one bundle of map packets");
  }

  @ParameterizedTest
  @ValueSource(strings = { "65x1", "1x65", "65x65", "8192x1" })
  void shouldRejectWallsWiderOrTallerThanTheLimit(final String text) {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
      ArgumentUtils.parseScreenDimensions(text)
    );

    final String message = exception.getMessage();
    assertEquals("Wall %s must be at most 64 maps on each side!".formatted(text), message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "100000x100000", "0x5", "5", "ax5" })
  void shouldRejectWallsThatAreNoValidDimensions(final String text) {
    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseScreenDimensions(text));
  }

  @Test
  void shouldCollectTheIdsOfTheSelectedPlayers() {
    final UUID firstId = UUID.randomUUID();
    final UUID secondId = UUID.randomUUID();
    final Player first = mock(Player.class);
    final Player second = mock(Player.class);
    when(first.getUniqueId()).thenReturn(firstId);
    when(second.getUniqueId()).thenReturn(secondId);
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(first, second);
    when(selector.values()).thenReturn(players);
    final Collection<UUID> ids = ArgumentUtils.resolvePlayerIds(selector);
    final List<UUID> expected = List.of(firstId, secondId);
    assertEquals(expected, ids);
  }

  @Test
  void shouldCollectNothingForAnEmptySelection() {
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of();
    when(selector.values()).thenReturn(players);
    final Collection<UUID> ids = ArgumentUtils.resolvePlayerIds(selector);
    final boolean empty = ids.isEmpty();
    assertTrue(empty);
  }

  @Test
  void shouldLetTheViewersOfEveryoneFollowThePlayersOnline() {
    final OnlinePlayers online = new OnlinePlayers();
    final MultiplePlayerSelector everyone = mock(MultiplePlayerSelector.class);
    when(everyone.inputString()).thenReturn("@a");

    final Set<UUID> expected = online.getPlayers();
    final Collection<UUID> viewers = ArgumentUtils.resolveViewers(everyone, online);
    assertSame(expected, viewers);
  }

  @Test
  void shouldMakeTheViewersOfAnyOtherSelectorThePlayersItMatchesNow() {
    final UUID id = UUID.randomUUID();
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(id);
    for (final String input : List.of("@a[distance=..20]", "Alice", "@p")) {
      final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
      when(selector.inputString()).thenReturn(input);
      when(selector.values()).thenReturn(List.of(player));

      final Collection<UUID> viewers = ArgumentUtils.resolveViewers(selector, new OnlinePlayers());
      final List<UUID> expectedViewers = List.of(id);
      assertEquals(expectedViewers, viewers, input);
    }
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> ArgumentUtils.parseDimensions(null));
    assertThrows(NullPointerException.class, () -> ArgumentUtils.parseScreenDimensions(null));
    assertThrows(NullPointerException.class, () -> ArgumentUtils.resolvePlayerIds(null));
    assertThrows(NullPointerException.class, () -> ArgumentUtils.resolveViewers(null, new OnlinePlayers()));
    assertThrows(NullPointerException.class, () -> ArgumentUtils.resolveViewers(mock(MultiplePlayerSelector.class), null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(ArgumentUtils.class);
  }
}
