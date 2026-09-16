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
 * Tests {@link ChatConfiguration}.
 */
final class ChatConfigurationTest {

  private static ChatConfiguration.Builder<?> completeBuilder() {
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();
    builder.viewers(List.of());
    builder.character(Characters.FULL_CHARACTER);
    builder.chatWidth(1);
    builder.chatHeight(1);
    return builder;
  }

  @Test
  void buildsAConfigurationWithEveryValue() {
    final List<UUID> viewers = new ArrayList<>();
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();
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
  void settersReturnTheSameBuilder() {
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();

    final Object afterViewers = builder.viewers(List.of());
    final Object afterCharacter = builder.character("x");
    final Object afterWidth = builder.chatWidth(1);
    final Object afterHeight = builder.chatHeight(1);

    assertInstanceOf(ChatConfiguration.ChatResultBuilder.class, builder);
    assertSame(builder, afterViewers);
    assertSame(builder, afterCharacter);
    assertSame(builder, afterWidth);
    assertSame(builder, afterHeight);
  }

  @Test
  void settersRejectNull() {
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();

    assertThrows(NullPointerException.class, () -> builder.viewers(null));
    assertThrows(NullPointerException.class, () -> builder.character(null));
  }

  @Test
  void rejectsMissingOrInvalidValues() {
    final ChatConfiguration.Builder<?> noViewers = ChatConfiguration.builder();
    noViewers.character("x");
    noViewers.chatWidth(1);
    noViewers.chatHeight(1);
    final ChatConfiguration.Builder<?> noCharacter = ChatConfiguration.builder();
    noCharacter.viewers(List.of());
    noCharacter.chatWidth(1);
    noCharacter.chatHeight(1);
    final ChatConfiguration.Builder<?> emptyCharacter = completeBuilder();
    emptyCharacter.character("");
    final ChatConfiguration.Builder<?> noWidth = completeBuilder();
    noWidth.chatWidth(0);
    final ChatConfiguration.Builder<?> noHeight = completeBuilder();
    noHeight.chatHeight(0);

    assertThrows(NullPointerException.class, noViewers::build);
    assertThrows(NullPointerException.class, noCharacter::build);
    assertThrows(IllegalArgumentException.class, emptyCharacter::build);
    assertThrows(IllegalArgumentException.class, noWidth::build);
    assertThrows(IllegalArgumentException.class, noHeight::build);
  }
}
