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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ScoreboardArgumentsTest {

  @ParameterizedTest
  @ValueSource(strings = { "24x1", "24x15", "1x15" })
  void shouldAcceptHeightsUpToTheLineLimit(final String resolution) {
    final boolean tooTall = ScoreboardArguments.isTooTall(resolution);
    assertFalse(tooTall);
  }

  @ParameterizedTest
  @ValueSource(strings = { "24x16", "8x100" })
  void shouldRefuseHeightsAboveTheLineLimit(final String resolution) {
    final boolean tooTall = ScoreboardArguments.isTooTall(resolution);
    assertTrue(tooTall);
  }

  @ParameterizedTest
  @ValueSource(strings = { "wide", "24x", "0x5", "0x16", "24X16", "24x16px", "24x9000" })
  void shouldLeaveResolutionsThatCannotBeParsedToTheCommand(final String resolution) {
    final boolean tooTall = ScoreboardArguments.isTooTall(resolution);
    assertFalse(tooTall);
  }

  @Test
  void shouldUseTheLineLimitOfTheLibrary() {
    final int limit = ScoreboardConfiguration.MAXIMUM_LINES;
    final boolean atLimit = ScoreboardArguments.isTooTall("24x%s".formatted(limit));
    final int above = limit + 1;
    final boolean aboveLimit = ScoreboardArguments.isTooTall("24x%s".formatted(above));
    assertFalse(atLimit);
    assertTrue(aboveLimit);
  }

  @Test
  void shouldRefuseANullResolution() {
    assertThrows(NullPointerException.class, () -> ScoreboardArguments.isTooTall(null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(ScoreboardArguments.class);
  }
}
