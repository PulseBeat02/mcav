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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ScoreboardConfiguration}.
 */
final class ScoreboardConfigurationTest {

  private static ScoreboardConfiguration.Builder<?> completeBuilder() {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of());
    builder.character(Characters.FULL_CHARACTER);
    builder.lines(1);
    builder.width(1);
    return builder;
  }

  @Test
  void buildsAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(viewers);
    builder.character(Characters.SMALL_BLACK_SQUARE);
    builder.lines(ScoreboardConfiguration.MAX_LINES);
    builder.width(30);

    final ScoreboardConfiguration configuration = builder.build();

    final Object configuredViewers = configuration.getViewers();
    final String character = configuration.getCharacter();
    final int lines = configuration.getLines();
    final int width = configuration.getWidth();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertEquals(Characters.SMALL_BLACK_SQUARE, character);
    assertEquals(15, lines);
    assertEquals(30, width);
  }

  @Test
  void settersReturnTheSameBuilder() {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterCharacter = builder.character("x");
    final Object afterLines = builder.lines(1);
    final Object afterWidth = builder.width(1);

    assertInstanceOf(ScoreboardConfiguration.ScoreboardResultBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterCharacter);
    assertSame(builder, afterLines);
    assertSame(builder, afterWidth);
  }

  @Test
  void settersRejectNull() {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();

    assertThrows(NullPointerException.class, () -> builder.viewers(null));
    assertThrows(NullPointerException.class, () -> builder.character(null));
  }

  @Test
  void rejectsMissingOrInvalidValues() {
    final ScoreboardConfiguration.Builder<?> noViewers = ScoreboardConfiguration.builder();
    noViewers.character("x");
    noViewers.lines(1);
    noViewers.width(1);
    final ScoreboardConfiguration.Builder<?> noCharacter = ScoreboardConfiguration.builder();
    noCharacter.viewers(List.of());
    noCharacter.lines(1);
    noCharacter.width(1);
    final ScoreboardConfiguration.Builder<?> emptyCharacter = completeBuilder();
    emptyCharacter.character("");
    final ScoreboardConfiguration.Builder<?> noLines = completeBuilder();
    noLines.lines(0);
    final ScoreboardConfiguration.Builder<?> tooManyLines = completeBuilder();
    tooManyLines.lines(16);
    final ScoreboardConfiguration.Builder<?> noWidth = completeBuilder();
    noWidth.width(0);

    assertThrows(NullPointerException.class, noViewers::build);
    assertThrows(NullPointerException.class, noCharacter::build);
    assertThrows(IllegalArgumentException.class, emptyCharacter::build);
    assertThrows(IllegalArgumentException.class, noLines::build);
    assertThrows(IllegalArgumentException.class, tooManyLines::build);
    assertThrows(IllegalArgumentException.class, noWidth::build);
  }
}
