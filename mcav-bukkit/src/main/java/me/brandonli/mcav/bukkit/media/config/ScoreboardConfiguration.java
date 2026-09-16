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

/**
 * Describes an image drawn onto the sidebar scoreboard, for use with the scoreboard image and scoreboard result.
 *
 * <p>Every pixel is drawn as the configured character in the color of the pixel, and every row of pixels becomes
 * one line of the sidebar. The sidebar shows at most 15 lines.
 *
 * <p>The viewers collection is not copied, so a concurrent collection can be passed. The scoreboard is shown to
 * the viewers that are online when the display starts.
 */
public class ScoreboardConfiguration {

  /**
   * The maximum number of lines the sidebar can show.
   */
  public static final int MAX_LINES = 15;

  private final Collection<UUID> viewers;
  private final String character;
  private final int lines;
  private final int width;

  private ScoreboardConfiguration(final Builder<?> builder, final Collection<UUID> viewers, final String character) {
    this.viewers = viewers;
    this.character = character;
    this.lines = builder.lines;
    this.width = builder.width;
  }

  /**
   * Gets the players who see the scoreboard.
   *
   * @return the UUIDs of the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the text drawn for every pixel, usually a single character such as {@code █}.
   *
   * @return the pixel text
   * @see me.brandonli.mcav.bukkit.media.result.Characters
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the height of the image in lines.
   *
   * @return the number of lines, at most {@link #MAX_LINES}
   */
  public int getLines() {
    return this.lines;
  }

  /**
   * Gets the width of the image in characters.
   *
   * @return the width in characters
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * The builder returned by {@link #builder()}.
   */
  public static final class ScoreboardResultBuilder extends Builder<ScoreboardResultBuilder> {

    ScoreboardResultBuilder() {
      // created through ScoreboardConfiguration.builder()
    }

    /**
     * Returns this builder with its concrete type.
     *
     * @return this builder
     */
    @Override
    protected ScoreboardResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder for a scoreboard configuration.
   *
   * @return a new builder
   */
  public static Builder<?> builder() {
    return new ScoreboardResultBuilder();
  }

  /**
   * Builds scoreboard configurations. Every value is required.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private @MonotonicNonNull Collection<UUID> viewers;
    private @MonotonicNonNull String character;
    private int lines;
    private int width;

    Builder() {
      // only subclassed inside this class
    }

    /**
     * Returns this builder with its concrete type, so the setters can be chained.
     *
     * @return this builder
     */
    abstract T self();

    /**
     * Sets the players who see the scoreboard. The collection is not copied, see {@link ScoreboardConfiguration}.
     *
     * @param viewers the UUIDs of the viewers
     * @return this builder
     * @throws NullPointerException if the viewers are null
     */
    public T viewers(final Collection<UUID> viewers) {
      Preconditions.checkNotNull(viewers, "Viewers must not be null");
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the text drawn for every pixel.
     *
     * @param character the pixel text, usually a single character such as {@code █}
     * @return this builder
     * @throws NullPointerException if the character is null
     */
    public T character(final String character) {
      Preconditions.checkNotNull(character, "Character must not be null");
      this.character = character;
      return this.self();
    }

    /**
     * Sets the height of the image in lines.
     *
     * @param lines the number of lines, from 1 to {@link #MAX_LINES}
     * @return this builder
     */
    public T lines(final int lines) {
      this.lines = lines;
      return this.self();
    }

    /**
     * Sets the width of the image in characters.
     *
     * @param width the width in characters, which must be positive
     * @return this builder
     */
    public T width(final int width) {
      this.width = width;
      return this.self();
    }

    /**
     * Builds the scoreboard configuration.
     *
     * @return the scoreboard configuration
     * @throws IllegalArgumentException if a size is out of range or the character is empty
     * @throws NullPointerException     if the viewers or the character were not set
     */
    public ScoreboardConfiguration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");
      final String configuredCharacter = Preconditions.checkNotNull(this.character, "Character must be set");

      Preconditions.checkArgument(!configuredCharacter.isEmpty(), "Character must not be empty");
      Preconditions.checkArgument(this.lines > 0 && this.lines <= MAX_LINES, "Lines must be between 1 and %s", MAX_LINES);
      Preconditions.checkArgument(this.width > 0, "Width must be positive");

      return new ScoreboardConfiguration(this, configuredViewers, configuredCharacter);
    }
  }
}
