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

final class ChatConfigurationTest {

  private static final String CHARACTER = "x";

  private ChatConfigurationBuilder completeBuilder() {
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();
    builder.viewers(List.of());
    builder.character(Characters.FULL_CHARACTER);
    builder.chatWidth(1);
    builder.chatHeight(1);
    return builder;
  }

  @Test
  void shouldBuildAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();
    builder.viewers(viewers);
    builder.character(Characters.BLACK_SQUARE);
    builder.chatWidth(40);
    builder.chatHeight(20);

    final ChatConfiguration configuration = builder.build();

    final Object configuredViewers = configuration.getViewers();
    final String character = configuration.getCharacter();
    final int width = configuration.getChatWidth();
    final int height = configuration.getChatHeight();
    assertSame(viewers, configuredViewers, "the viewers are not copied");
    assertEquals(Characters.BLACK_SQUARE, character);
    assertEquals(40, width);
    assertEquals(20, height);
  }

  @Test
  void shouldReturnTheSameBuilderFromSetters() {
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterCharacter = builder.character(CHARACTER);
    final Object afterWidth = builder.chatWidth(1);
    final Object afterHeight = builder.chatHeight(1);

    assertInstanceOf(DefaultChatConfigurationBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterCharacter);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
  }

  @Test
  void shouldRejectNullInSetters() {
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();

    final NullPointerException nullViewersException = assertThrows(NullPointerException.class, () -> builder.viewers(null));
    final NullPointerException nullCharacterException = assertThrows(NullPointerException.class, () -> builder.character(null));
    final String nullViewersMessage = nullViewersException.getMessage();
    final String nullCharacterMessage = nullCharacterException.getMessage();
    assertEquals("Viewers must not be null!", nullViewersMessage);
    assertEquals("Character must not be null!", nullCharacterMessage);
  }

  @Test
  void shouldRejectMissingOrInvalidValues() {
    final ChatConfigurationBuilder noViewers = ChatConfiguration.builder();
    noViewers.character(CHARACTER);
    noViewers.chatWidth(1);
    noViewers.chatHeight(1);
    final ChatConfigurationBuilder noCharacter = ChatConfiguration.builder();
    noCharacter.viewers(List.of());
    noCharacter.chatWidth(1);
    noCharacter.chatHeight(1);
    final ChatConfigurationBuilder emptyCharacter = this.completeBuilder();
    emptyCharacter.character("");
    final ChatConfigurationBuilder noWidth = this.completeBuilder();
    noWidth.chatWidth(0);
    final ChatConfigurationBuilder noHeight = this.completeBuilder();
    noHeight.chatHeight(0);

    final IllegalStateException noViewersException = assertThrows(IllegalStateException.class, noViewers::build);
    final IllegalStateException noCharacterException = assertThrows(IllegalStateException.class, noCharacter::build);
    final IllegalArgumentException emptyCharacterException = assertThrows(IllegalArgumentException.class, emptyCharacter::build);
    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, noWidth::build);
    final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, noHeight::build);
    final String noViewersMessage = noViewersException.getMessage();
    final String noCharacterMessage = noCharacterException.getMessage();
    final String emptyCharacterMessage = emptyCharacterException.getMessage();
    final String noWidthMessage = noWidthException.getMessage();
    final String noHeightMessage = noHeightException.getMessage();
    assertEquals("Viewers must be set!", noViewersMessage);
    assertEquals("Character must be set!", noCharacterMessage);
    assertEquals("Character must not be empty!", emptyCharacterMessage);
    assertEquals("Chat width 0 must be positive!", noWidthMessage);
    assertEquals("Chat height 0 must be positive!", noHeightMessage);
  }
}
