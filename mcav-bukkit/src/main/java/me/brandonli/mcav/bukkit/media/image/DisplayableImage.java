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
 * <p>Displaying an image may resize the supplied buffer in place; maps resize only when configured. The caller
 * retains ownership of the buffer and may release it after {@link #displayImage(ImageBuffer)} returns because
 * renderers queue converted frame data, not the image itself. Do not mutate or close the buffer during that call.
 * Call {@link #release()} when finished. Blocks and scoreboards restore saved state, entities are removed,
 * and maps and chat are cleared; map pixels and chat history from before the display are not restored.
 *
 * <pre><code>
 *   final DisplayableImage display = DisplayableImage.map(configuration, DitherAlgorithm.filterLite());
 *   display.displayImage(image);
 *   // later
 *   display.release();
 * </code></pre>
 *
 * <p>Calls may originate on a worker thread, but serialize display and release operations for a given instance.
 * For block, entity and scoreboard displays, release on the server main thread during plugin shutdown so cleanup
 * does not depend on a disabled plugin scheduling another task. Instances can display another image after release.
 */
public interface DisplayableImage {
  /**
   * Shows the image on the display, replacing the previous image. May be called from any thread.
   *
   * @param image the non-null, open caller-owned image, which may be resized in place; do not share it with
   *              another writer during this call
   * @throws NullPointerException if the image is null
   */
  void displayImage(final ImageBuffer image);

  /**
   * Releases the current display: removes its entity, restores saved block/scoreboard state, or clears maps/chat.
   * May be called from any thread while the plugin is enabled; use the main thread during plugin shutdown.
   * Buffers passed to {@link #displayImage(ImageBuffer)} and caller-supplied dithering algorithms remain caller-owned.
   */
  void release();

  /**
   * Creates a display that shows images on a grid of maps.
   *
   * <p>The algorithm is retained and never released by the display. Serialize calls if it keeps temporal state.
   *
   * @param configuration the map configuration
   * @param algorithm     the dithering algorithm used to reduce images to the map palette
   * @return the map display
   * @throws NullPointerException if {@code configuration} or {@code algorithm} is null
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
   * @throws NullPointerException if {@code configuration} is null
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
   * @throws NullPointerException if {@code configuration} is null
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
   * @throws NullPointerException if {@code configuration} is null
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
   * @throws NullPointerException if {@code configuration} is null
   */
  static DisplayableImage block(final BlockConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Block configuration must not be null");
    return new BlockImage(configuration);
  }
}
