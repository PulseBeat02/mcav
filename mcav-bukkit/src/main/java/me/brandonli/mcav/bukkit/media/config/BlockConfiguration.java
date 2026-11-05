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
import me.brandonli.mcav.bukkit.media.image.BlockImage;
import me.brandonli.mcav.bukkit.media.result.BlockResult;
import org.bukkit.Location;

/**
 * Describes a wall of blocks that shows an image, for use with {@link BlockImage} and {@link BlockResult}.
 *
 * <p>Every pixel becomes one block. The wall stands upright along the x axis, is centered horizontally on the
 * configured position, and grows upward from it. Blocks are sent as fake block changes, so the world is never
 * modified, and the original blocks are shown again when the display is released.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 *
 * <p>The position is retained by reference. Finish setting it before creating the display and do not
 * mutate it while rendering. {@link #getPosition()} returns that same mutable location.
 *
 * <p>This class is immutable and thread-safe, except for the viewer collection and the position, which it shares with
 * the caller instead of copying.
 *
 * {@snippet :
 * final int blockWidth = 64;
 * final int blockHeight = 36;
 * final BlockConfigurationBuilder builder = BlockConfiguration.builder();
 * final BlockConfiguration configuration = builder
 *   .viewers(viewers)
 *   .position(position)
 *   .blockWidth(blockWidth)
 *   .blockHeight(blockHeight)
 *   .build();
 * }
 *
 * @since 1.0.0
 */
public final class BlockConfiguration {

  private final Collection<UUID> viewers;
  private final int blockWidth;
  private final int blockHeight;
  private final Location position;

  BlockConfiguration(final Collection<UUID> viewers, final int blockWidth, final int blockHeight, final Location position) {
    this.viewers = viewers;
    this.blockWidth = blockWidth;
    this.blockHeight = blockHeight;
    this.position = position;
  }

  /**
   * Creates a new builder for a block configuration.
   *
   * @return a new builder
   * @since 1.0.0
   */
  public static BlockConfigurationBuilder builder() {
    return new DefaultBlockConfigurationBuilder();
  }

  /**
   * Returns the players who see the block wall.
   *
   * @return the original mutable-or-immutable collection supplied to the builder, without a copy; use a
   *         concurrent collection if another thread changes membership during rendering
   * @since 1.0.0
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Returns the width of the wall in blocks.
   *
   * @return the width in blocks
   * @since 1.0.0
   */
  public int getBlockWidth() {
    return this.blockWidth;
  }

  /**
   * Returns the height of the wall in blocks.
   *
   * @return the height in blocks
   * @since 1.0.0
   */
  public int getBlockHeight() {
    return this.blockHeight;
  }

  /**
   * Returns the bottom center of the wall.
   *
   * @return the original location supplied to the builder, without a defensive copy
   * @since 1.0.0
   */
  public Location getPosition() {
    return this.position;
  }
}
