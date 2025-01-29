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
import me.brandonli.mcav.bukkit.media.result.FunctionalVideoFilter;
import me.brandonli.mcav.bukkit.media.result.ScoreboardResult;

/**
 * Represents the configuration of a scoreboard. This class contains the properties
 * necessary for configuring a scoreboard, such as viewers, character, number of lines,
 * and width. Instances of this class are immutable and can be created using the
 * {@link Builder}.
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
   * Retrieves the collection of viewers associated with the scoreboard configuration.
   * Each viewer is represented by a unique {@link UUID}.
   *
   * @return a collection of {@link UUID} objects representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Retrieves the character associated with the scoreboard configuration.
   *
   * @return a string representing the configured character
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Retrieves the number of lines configured in the scoreboard.
   *
   * @return the number of lines as an integer
   */
  public int getLines() {
    return this.lines;
  }

  /**
   * Retrieves the width of the scoreboard configuration.
   *
   * @return the width of the scoreboard as an integer
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Builder class for constructing instances of {@link ScoreboardResult}.
   * This class provides methods to set the properties of the scoreboard result.
   */
  public static final class ScoreboardResultBuilder extends Builder<ScoreboardResultBuilder> {

    @Override
    protected ScoreboardResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates and returns a new instance of the builder for constructing a {@link ScoreboardConfiguration}.
   * The builder provides methods for configuring the properties of the scoreboard configuration,
   * such as viewers, character, lines, and width, and ensures valid construction of the object.
   *
   * @return a {@link Builder} instance for configuring and constructing a {@link ScoreboardConfiguration}
   */
  public static Builder<?> builder() {
    return new ScoreboardResultBuilder();
  }

  /**
   * Abstract builder class for creating instances of {@link ScoreboardResult}.
   * This class provides methods to set the properties of the scoreboard result.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int lines;
    private int width;

    protected abstract T self();

    /**
     * Sets the viewers for the builder.
     *
     * @param viewers the collection of UUIDs representing the viewers
     * @return the builder instance for method chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character to be used and returns the current builder instance.
     *
     * @param character the character to be used
     * @return the current builder instance
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the number of lines and returns the builder instance.
     *
     * @param lines the number of lines to be set; must be a positive integer
     * @return the current builder instance
     */
    public T lines(final int lines) {
      this.lines = lines;
      return this.self();
    }

    /**
     * Sets the width property for the builder.
     *
     * @param width the width value to be set; must be a positive integer
     * @return the builder instance for method chaining
     */
    public T width(final int width) {
      this.width = width;
      return this.self();
    }

    /**
     * Builds and returns an instance of {@link FunctionalVideoFilter}.
     * This method performs validation checks to ensure all required fields have been set.
     *
     * @return a new instance of {@link FunctionalVideoFilter} with the configured properties.
     * @throws NullPointerException     if any mandatory field (viewers or character) is null.
     * @throws IllegalArgumentException if the lines or width values are non-positive.
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
