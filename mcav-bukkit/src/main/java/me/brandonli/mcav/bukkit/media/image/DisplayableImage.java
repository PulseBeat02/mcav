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
package me.brandonli.mcav.bukkit.media.image;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Shows still images to players on one of the supported displays: maps, chat, text display entities, the sidebar
 * scoreboard, or a wall of blocks.
 *
 * <p>Displaying an image resizes it to the size of the display, so the image buffer passed in is modified. Call
 * {@link #release()} when the image is no longer needed to remove it and restore whatever was there before.
 *
 * <pre><code>
 *   final DisplayableImage display = DisplayableImage.map(configuration, DitherAlgorithm.filterLite());
 *   display.displayImage(image);
 *   // later
 *   display.release();
 * </code></pre>
 */
public interface DisplayableImage {
  /**
   * Shows the image on the display, replacing the previous image. May be called from any thread.
   *
   * @param image the image to show, which is resized to the display in place
   */
  void displayImage(final ImageBuffer image);

  /**
   * Removes the image from the display and restores whatever was shown before. May be called from any thread.
   */
  void release();

  /**
   * Creates a display that shows images on a grid of maps.
   *
   * @param configuration the map configuration
   * @param algorithm     the dithering algorithm used to reduce images to the map palette
   * @return the map display
   */
  static DisplayableImage map(final MapConfiguration configuration, final DitherAlgorithm algorithm) {
    Preconditions.checkNotNull(configuration, "Map configuration must not be null");
    Preconditions.checkNotNull(algorithm, "Dither algorithm must not be null");
    return new MapImage(configuration, algorithm);
  }

  /**
   * Creates a display that shows images in the chat.
   *
   * @param configuration the chat configuration
   * @return the chat display
   */
  static DisplayableImage chat(final ChatConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Chat configuration must not be null");
    return new ChatImage(configuration);
  }

  /**
   * Creates a display that shows images as colored text inside a text display entity.
   *
   * @param configuration the entity configuration
   * @return the entity display
   */
  static DisplayableImage entity(final EntityConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Entity configuration must not be null");
    return new EntityImage(configuration);
  }

  /**
   * Creates a display that shows images on the sidebar scoreboard.
   *
   * @param configuration the scoreboard configuration
   * @return the scoreboard display
   */
  static DisplayableImage scoreboard(final ScoreboardConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Scoreboard configuration must not be null");
    return new ScoreboardImage(configuration);
  }

  /**
   * Creates a display that shows images as a wall of blocks.
   *
   * @param configuration the block configuration
   * @return the block display
   */
  static DisplayableImage block(final BlockConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Block configuration must not be null");
    return new BlockImage(configuration);
  }
}
