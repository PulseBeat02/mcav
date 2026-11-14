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

final class ScoreboardConfigurationTest {

  private static final String CHARACTER = "x";

  private ScoreboardConfigurationBuilder completeBuilder() {
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of());
    builder.character(Characters.FULL_CHARACTER);
    builder.lines(1);
    builder.width(1);
    return builder;
  }

  @Test
  void shouldBuildAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
    builder.viewers(viewers);
    builder.character(Characters.SMALL_BLACK_SQUARE);
    builder.lines(ScoreboardConfiguration.MAXIMUM_LINES);
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
  void shouldReturnTheSameBuilderFromSetters() {
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterCharacter = builder.character(CHARACTER);
    final Object afterLines = builder.lines(1);
    final Object afterWidth = builder.width(1);

    assertInstanceOf(DefaultScoreboardConfigurationBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterCharacter);
    assertSame(builder, afterLines);
    assertSame(builder, afterWidth);
  }

  @Test
  void shouldRejectNullInSetters() {
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();

    final NullPointerException nullViewersException = assertThrows(NullPointerException.class, () -> builder.viewers(null));
    final NullPointerException nullCharacterException = assertThrows(NullPointerException.class, () -> builder.character(null));
    final String nullViewersMessage = nullViewersException.getMessage();
    final String nullCharacterMessage = nullCharacterException.getMessage();
    assertEquals("Viewers must not be null!", nullViewersMessage);
    assertEquals("Character must not be null!", nullCharacterMessage);
  }

  @Test
  void shouldRejectMissingOrInvalidValues() {
    final ScoreboardConfigurationBuilder noViewers = ScoreboardConfiguration.builder();
    noViewers.character(CHARACTER);
    noViewers.lines(1);
    noViewers.width(1);
    final ScoreboardConfigurationBuilder noCharacter = ScoreboardConfiguration.builder();
    noCharacter.viewers(List.of());
    noCharacter.lines(1);
    noCharacter.width(1);
    final ScoreboardConfigurationBuilder emptyCharacter = this.completeBuilder();
    emptyCharacter.character("");
    final ScoreboardConfigurationBuilder noLines = this.completeBuilder();
    noLines.lines(0);
    final ScoreboardConfigurationBuilder tooManyLines = this.completeBuilder();
    tooManyLines.lines(16);
    final ScoreboardConfigurationBuilder noWidth = this.completeBuilder();
    noWidth.width(0);

    final IllegalStateException noViewersException = assertThrows(IllegalStateException.class, noViewers::build);
    final IllegalStateException noCharacterException = assertThrows(IllegalStateException.class, noCharacter::build);
    final IllegalArgumentException emptyCharacterException = assertThrows(IllegalArgumentException.class, emptyCharacter::build);
    final IllegalArgumentException noLinesException = assertThrows(IllegalArgumentException.class, noLines::build);
    final IllegalArgumentException tooManyLinesException = assertThrows(IllegalArgumentException.class, tooManyLines::build);
    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, noWidth::build);
    final String noViewersMessage = noViewersException.getMessage();
    final String noCharacterMessage = noCharacterException.getMessage();
    final String emptyCharacterMessage = emptyCharacterException.getMessage();
    final String noLinesMessage = noLinesException.getMessage();
    final String tooManyLinesMessage = tooManyLinesException.getMessage();
    final String noWidthMessage = noWidthException.getMessage();
    assertEquals("Viewers must be set!", noViewersMessage);
    assertEquals("Character must be set!", noCharacterMessage);
    assertEquals("Character must not be empty!", emptyCharacterMessage);
    assertEquals("Lines 0 must be between 1 and 15 inclusive!", noLinesMessage);
    assertEquals("Lines 16 must be between 1 and 15 inclusive!", tooManyLinesMessage);
    assertEquals("Width 0 must be positive!", noWidthMessage);
  }
}
