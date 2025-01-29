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
package me.brandonli.mcav.bukkit.media.image;

import me.brandonli.mcav.bukkit.media.config.*;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Represents a DisplayableImage provider that can be used to display {@link ImageBuffer} images on command.
 */
public interface DisplayableImage {
  /**
   * Displays the given image on the configured display.
   *
   * @param image the image to display
   */
  void displayImage(final ImageBuffer image);

  /**
   * Releases any resources associated with this displayable image.
   */
  void release();

  /**
   * Creates a DisplayableImage for the given MapConfiguration.
   *
   * @param mapConfiguration the configuration for the map
   * @param algorithm        the dithering algorithm to use
   * @return a DisplayableImage instance for the map
   */
  static DisplayableImage map(final MapConfiguration mapConfiguration, final DitherAlgorithm algorithm) {
    return new MapImage(mapConfiguration, algorithm);
  }

  /**
   * Creates a DisplayableImage for the given ChatConfiguration.
   *
   * @param chatConfiguration the configuration for the chat
   * @return a DisplayableImage instance for the chat
   */
  static DisplayableImage chat(final ChatConfiguration chatConfiguration) {
    return new ChatImage(chatConfiguration);
  }

  /**
   * Creates a DisplayableImage for the given EntityConfiguration.
   *
   * @param entityConfiguration the configuration for the entity
   * @return a DisplayableImage instance for the entity
   */
  static DisplayableImage entity(final EntityConfiguration entityConfiguration) {
    return new EntityImage(entityConfiguration);
  }

  /**
   * Creates a DisplayableImage for the given ScoreboardConfiguration.
   *
   * @param scoreboardConfiguration the configuration for the scoreboard
   * @return a DisplayableImage instance for the scoreboard
   */
  static DisplayableImage scoreboard(final ScoreboardConfiguration scoreboardConfiguration) {
    return new ScoreboardImage(scoreboardConfiguration);
  }

  /**
   * Creates a DisplayableImage for the given BlockConfiguration.
   *
   * @param blockConfiguration the configuration for the block
   * @return a DisplayableImage instance for the block
   */
  static DisplayableImage block(final BlockConfiguration blockConfiguration) {
    return new BlockImage(blockConfiguration);
  }
}
