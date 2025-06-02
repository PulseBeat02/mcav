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
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * The DisplayableImage interface defines a contract for displaying images of type StaticImage.
 * Classes implementing this interface should provide the implementation for the display method
 * to render or handle the provided StaticImage as per specific requirements.
 */
public interface DisplayableImage {
  /**
   * Displays the specified static image. This method is intended to be implemented
   * by classes that handle rendering or processing of images of type StaticImage.
   *
   * @param image the static image to be displayed; must not be null
   */
  void displayImage(final StaticImage image);

  /**
   * Releases any resources associated with the DisplayableImage instance.
   * <p>
   * This method should be called to clean up resources when the instance is no longer needed.
   * Implementations of this method ensure that any system or memory resources used are properly released.
   */
  void release();

  /**
   * Maps the provided map configuration and dithering algorithm into a displayable image.
   *
   * @param mapConfiguration the configuration for the map, including dimensions, resolution, and viewers; must not be null
   * @param algorithm        the dithering algorithm to apply when generating the image; must not be null
   * @return a {@code DisplayableImage} instance constructed based on the provided map configuration and dithering algorithm
   */
  static DisplayableImage map(final MapConfiguration mapConfiguration, final DitherAlgorithm algorithm) {
    return new MapImage(mapConfiguration, algorithm);
  }

  /**
   * Creates a DisplayableImage instance for displaying a chat-based image,
   * using the specified ChatConfiguration to define the display settings.
   *
   * @param chatConfiguration the configuration object specifying parameters such as
   *                          the audience, dimensions, and character used for rendering the image
   * @return a DisplayableImage instance that can render the chat-based image
   */
  static DisplayableImage chat(final ChatConfiguration chatConfiguration) {
    return new ChatImage(chatConfiguration);
  }

  /**
   * Creates and returns a DisplayableImage instance configured for an entity based on the given
   * EntityConfiguration. The resulting DisplayableImage represents the visual representation of
   * an entity, built with the specified configuration properties such as dimensions, position,
   * viewers, and associated character.
   *
   * @param entityConfiguration the configuration details for the entity, including its dimensions,
   *                            position, viewers, and associated character; must not be null
   * @return a DisplayableImage instance that represents the entity as per the provided configuration
   */
  static DisplayableImage entity(final EntityConfiguration entityConfiguration) {
    return new EntityImage(entityConfiguration);
  }

  /**
   * Creates a new {@link DisplayableImage} instance that represents a scoreboard configured
   * using the provided {@link ScoreboardConfiguration}.
   *
   * @param scoreboardConfiguration the configuration for the scoreboard; must not be null
   * @return a {@link DisplayableImage} instance configured for the given scoreboard
   */
  static DisplayableImage scoreboard(final ScoreboardConfiguration scoreboardConfiguration) {
    return new ScoreboardImage(scoreboardConfiguration);
  }

  static DisplayableImage block(final BlockConfiguration blockConfiguration) {
    return new BlockImage(blockConfiguration);
  }
}
