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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultChatConfigurationBuilder implements ChatConfigurationBuilder {

  private @MonotonicNonNull Collection<UUID> viewers;
  private @MonotonicNonNull String character;
  private int chatWidth;
  private int chatHeight;

  DefaultChatConfigurationBuilder() {}

  @Override
  public ChatConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public ChatConfigurationBuilder character(final String character) {
    Preconditions.checkNotNull(character, "Character must not be null!");

    this.character = character;
    return this;
  }

  @Override
  public ChatConfigurationBuilder chatWidth(final int chatWidth) {
    this.chatWidth = chatWidth;
    return this;
  }

  @Override
  public ChatConfigurationBuilder chatHeight(final int chatHeight) {
    this.chatHeight = chatHeight;
    return this;
  }

  @Override
  public ChatConfiguration build() {
    final @Nullable Collection<UUID> configuredViewers = this.viewers;
    if (configuredViewers == null) {
      final String message = "Viewers must be set!";
      throw new IllegalStateException(message);
    }

    final @Nullable String configuredCharacter = this.character;
    if (configuredCharacter == null) {
      final String message = "Character must be set!";
      throw new IllegalStateException(message);
    }

    Preconditions.checkArgument(!configuredCharacter.isEmpty(), "Character must not be empty!");
    Preconditions.checkArgument(this.chatWidth > 0, "Chat width %s must be positive!", this.chatWidth);
    Preconditions.checkArgument(this.chatHeight > 0, "Chat height %s must be positive!", this.chatHeight);

    return new ChatConfiguration(configuredViewers, configuredCharacter, this.chatWidth, this.chatHeight);
  }
}
