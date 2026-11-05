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

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.image.ScoreboardImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.bukkit.media.result.ScoreboardResult;

/**
 * Describes an image drawn onto the sidebar scoreboard, for use with {@link ScoreboardImage} and
 * {@link ScoreboardResult}.
 *
 * <p>Every pixel is drawn as the configured character in the color of the pixel, and every row of pixels becomes
 * one line of the sidebar. The sidebar shows at most 15 lines.
 *
 * <p>The viewers collection is not copied, so a concurrent collection can be passed. The renderer checks membership each server tick,
 * shows the board to new or returning online viewers, and restores removed viewers who still see its board.
 *
 * <p>This class is immutable and thread-safe, except for the viewer collection, which it shares with the caller instead
 * of copying.
 *
 * {@snippet :
 * final int width = 16;
 * final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
 * final ScoreboardConfiguration configuration = builder
 *   .viewers(viewers)
 *   .character(Characters.BLACK_SQUARE)
 *   .lines(ScoreboardConfiguration.MAXIMUM_LINES)
 *   .width(width)
 *   .build();
 * }
 *
 * @since 1.0.0
 */
public final class ScoreboardConfiguration {

  /**
   * The maximum number of lines the sidebar can show.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_LINES = 15;

  private final Collection<UUID> viewers;
  private final String character;
  private final int lines;
  private final int width;

  ScoreboardConfiguration(final Collection<UUID> viewers, final String character, final int lines, final int width) {
    this.viewers = viewers;
    this.character = character;
    this.lines = lines;
    this.width = width;
  }

  /**
   * Creates a new builder for a scoreboard configuration.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static ScoreboardConfigurationBuilder builder() {
    return new DefaultScoreboardConfigurationBuilder();
  }

  /**
   * Returns the players who see the scoreboard.
   *
   * @return the original mutable-or-immutable collection supplied to the builder, without a copy; use a
   *         concurrent collection if another thread changes membership during rendering
   * @since 1.0.0
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Returns the text drawn for every pixel, usually a single character such as {@code █}.
   *
   * @return the pixel text
   * @see Characters
   * @since 1.0.0
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Returns the height of the image in lines.
   *
   * @return the number of lines, at most {@link #MAXIMUM_LINES}
   * @since 1.0.0
   */
  public int getLines() {
    return this.lines;
  }

  /**
   * Returns the width of the image in characters.
   *
   * @return the width in characters
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }
}
