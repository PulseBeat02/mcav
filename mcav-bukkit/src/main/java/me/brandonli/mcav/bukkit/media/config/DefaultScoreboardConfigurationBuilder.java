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

final class DefaultScoreboardConfigurationBuilder implements ScoreboardConfigurationBuilder {

  private @MonotonicNonNull Collection<UUID> viewers;
  private @MonotonicNonNull String character;
  private int lines;
  private int width;

  DefaultScoreboardConfigurationBuilder() {}

  @Override
  public ScoreboardConfigurationBuilder viewers(final Collection<UUID> viewers) {
    Preconditions.checkNotNull(viewers, "Viewers must not be null!");

    this.viewers = viewers;
    return this;
  }

  @Override
  public ScoreboardConfigurationBuilder character(final String character) {
    Preconditions.checkNotNull(character, "Character must not be null!");

    this.character = character;
    return this;
  }

  @Override
  public ScoreboardConfigurationBuilder lines(final int lines) {
    this.lines = lines;
    return this;
  }

  @Override
  public ScoreboardConfigurationBuilder width(final int width) {
    this.width = width;
    return this;
  }

  @Override
  public ScoreboardConfiguration build() {
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
    Preconditions.checkArgument(
      this.lines > 0 && this.lines <= ScoreboardConfiguration.MAXIMUM_LINES,
      "Lines %s must be between 1 and %s inclusive!",
      this.lines,
      ScoreboardConfiguration.MAXIMUM_LINES
    );
    Preconditions.checkArgument(this.width > 0, "Width %s must be positive!", this.width);

    return new ScoreboardConfiguration(configuredViewers, configuredCharacter, this.lines, this.width);
  }
}
