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
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class EntityConfigurationTest {

  private static final String CHARACTER = "x";

  // a location refers to its world only weakly, so the test keeps the world itself
  private static final World WORLD = mock(World.class);
  private static final Location POSITION = new Location(WORLD, 1, 2, 3);

  private EntityConfigurationBuilder completeBuilder() {
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();
    builder.viewers(List.of());
    builder.character(Characters.FULL_CHARACTER);
    builder.position(POSITION);
    builder.entityWidth(1);
    builder.entityHeight(1);
    return builder;
  }

  @Test
  void shouldBuildAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();
    builder.viewers(viewers);
    builder.character(Characters.WHITE_CIRCLE);
    builder.position(POSITION);
    builder.entityWidth(64);
    builder.entityHeight(32);

    final EntityConfiguration configuration = builder.build();

    final Object configuredViewers = configuration.getViewers();
    final String character = configuration.getCharacter();
    final Location position = configuration.getPosition();
    final int width = configuration.getEntityWidth();
    final int height = configuration.getEntityHeight();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertEquals(Characters.WHITE_CIRCLE, character);
    assertSame(POSITION, position);
    assertEquals(64, width);
    assertEquals(32, height);
  }

  @Test
  void shouldReturnTheSameBuilderFromSetters() {
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterCharacter = builder.character(CHARACTER);
    final Object afterPosition = builder.position(POSITION);
    final Object afterWidth = builder.entityWidth(1);
    final Object afterHeight = builder.entityHeight(1);

    assertInstanceOf(DefaultEntityConfigurationBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterCharacter);
    assertSame(builder, afterPosition);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
  }

  @Test
  void shouldRejectNullInSetters() {
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();

    assertThrows(NullPointerException.class, () -> builder.viewers(null));
    assertThrows(NullPointerException.class, () -> builder.character(null));
    assertThrows(NullPointerException.class, () -> builder.position(null));
  }

  @Test
  void shouldRejectMissingValues() {
    final EntityConfigurationBuilder noViewers = EntityConfiguration.builder();
    noViewers.character(CHARACTER);
    noViewers.position(POSITION);
    noViewers.entityWidth(1);
    noViewers.entityHeight(1);
    final EntityConfigurationBuilder noCharacter = EntityConfiguration.builder();
    noCharacter.viewers(List.of());
    noCharacter.position(POSITION);
    noCharacter.entityWidth(1);
    noCharacter.entityHeight(1);
    final EntityConfigurationBuilder noPosition = EntityConfiguration.builder();
    noPosition.viewers(List.of());
    noPosition.character(CHARACTER);
    noPosition.entityWidth(1);
    noPosition.entityHeight(1);

    assertThrows(NullPointerException.class, noViewers::build);
    assertThrows(NullPointerException.class, noCharacter::build);
    assertThrows(NullPointerException.class, noPosition::build);
  }

  @Test
  void shouldRejectInvalidValues() {
    final EntityConfigurationBuilder emptyCharacter = this.completeBuilder();
    emptyCharacter.character("");
    final EntityConfigurationBuilder noWidth = this.completeBuilder();
    noWidth.entityWidth(0);
    final EntityConfigurationBuilder noHeight = this.completeBuilder();
    noHeight.entityHeight(0);
    final EntityConfigurationBuilder noWorld = this.completeBuilder();
    final Location nowhere = new Location(null, 1, 2, 3);
    noWorld.position(nowhere);

    final IllegalArgumentException emptyCharacterException = assertThrows(IllegalArgumentException.class, emptyCharacter::build);
    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, noWidth::build);
    final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, noHeight::build);
    final IllegalArgumentException noWorldException = assertThrows(IllegalArgumentException.class, noWorld::build);
    final String emptyCharacterMessage = emptyCharacterException.getMessage();
    final String noWidthMessage = noWidthException.getMessage();
    final String noHeightMessage = noHeightException.getMessage();
    final String noWorldMessage = noWorldException.getMessage();
    assertEquals("Character must not be empty!", emptyCharacterMessage);
    assertEquals("Entity width 0 must be positive!", noWidthMessage);
    assertEquals("Entity height 0 must be positive!", noHeightMessage);
    assertEquals("Position must have a world!", noWorldMessage);
  }
}
