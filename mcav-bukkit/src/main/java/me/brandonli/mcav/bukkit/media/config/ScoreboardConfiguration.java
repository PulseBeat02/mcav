/*
 * This file is part of mcav, a media playback library for Minecraft
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

/**
 * Represents a configuration for scoreboard related prototypes.
 */
public class ScoreboardConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int lines;
  private final int width;

  private ScoreboardConfiguration(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.lines = builder.lines;
    this.width = builder.width;
  }

  /**
   * Gets the viewers of this scoreboard configuration.
   *
   * @return the collection of UUIDs representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the character string associated with this scoreboard configuration.
   *
   * @return the character string, which may represent a specific
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the number of lines in the scoreboard.
   *
   * @return the line count of the scoreboard
   */
  public int getLines() {
    return this.lines;
  }

  /**
   * Gets the width of the scoreboard.
   *
   * @return the character width of the scoreboard
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Scoreboard configuration builder abstraction.
   */
  public static final class ScoreboardResultBuilder extends Builder<ScoreboardResultBuilder> {

    ScoreboardResultBuilder() {
      // no-op
    }

    @Override
    protected ScoreboardResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new scoreboard configuration builder.
   *
   * @return a new scoreboard configuration builder
   */
  public static Builder<?> builder() {
    return new ScoreboardResultBuilder();
  }

  /**
   * Abstract builder for scoreboard configurations.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int lines;
    private int width;

    Builder() {
      // no-op
    }

    abstract T self();

    /**
     * Sets the viewers of this scoreboard configuration.
     *
     * @param viewers the viewers to set
     * @return the builder instance for chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character string of this scoreboard configuration.
     *
     * @param character the character value to be set
     * @return the instance of the builder for method chaining
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the scoreboard lines of this scoreboard configuration.
     *
     * @param lines the number of lines to be set
     * @return the current builder instance
     */
    public T lines(final int lines) {
      this.lines = lines;
      return this.self();
    }

    /**
     * Sets the character width of the scoreboard.
     *
     * @param width the width to be set
     * @return the current builder instance
     */
    public T width(final int width) {
      this.width = width;
      return this.self();
    }

    /**
     * Builds the scoreboard configuration.
     *
     * @return a new instance of ScoreboardConfiguration
     */
    public ScoreboardConfiguration build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkArgument(this.lines > 0, "Lines must be positive");
      Preconditions.checkArgument(this.width > 0, "Width must be positive");
      return new ScoreboardConfiguration(this);
    }
  }
}
