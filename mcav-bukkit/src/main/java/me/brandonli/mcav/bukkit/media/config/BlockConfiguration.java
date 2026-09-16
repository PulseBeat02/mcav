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
import org.bukkit.Location;
import org.bukkit.World;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Describes a wall of blocks that shows an image, for use with the block image and block result.
 *
 * <p>Every pixel becomes one block. The wall stands upright along the x axis, is centered horizontally on the
 * configured position, and grows upward from it. Blocks are sent as fake block changes, so the world is never
 * modified, and the original blocks are shown again when the display is released.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 */
public class BlockConfiguration {

  private final Collection<UUID> viewers;
  private final int blockWidth;
  private final int blockHeight;
  private final Location position;

  private BlockConfiguration(final Builder<?> builder, final Collection<UUID> viewers, final Location position) {
    this.viewers = viewers;
    this.blockWidth = builder.blockWidth;
    this.blockHeight = builder.blockHeight;
    this.position = position;
  }

  /**
   * Gets the players who see the block wall.
   *
   * @return the UUIDs of the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the width of the wall in blocks.
   *
   * @return the width in blocks
   */
  public int getBlockWidth() {
    return this.blockWidth;
  }

  /**
   * Gets the height of the wall in blocks.
   *
   * @return the height in blocks
   */
  public int getBlockHeight() {
    return this.blockHeight;
  }

  /**
   * Gets the bottom center of the wall.
   *
   * @return the position of the wall
   */
  public Location getPosition() {
    return this.position;
  }

  /**
   * The builder returned by {@link #builder()}.
   */
  public static final class BlockResultBuilder extends Builder<BlockResultBuilder> {

    BlockResultBuilder() {
      // created through BlockConfiguration.builder()
    }

    /**
     * Returns this builder with its concrete type.
     *
     * @return this builder
     */
    @Override
    protected BlockResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder for a block configuration.
   *
   * @return a new builder
   */
  public static Builder<?> builder() {
    return new BlockResultBuilder();
  }

  /**
   * Builds block configurations. Every value is required.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private @MonotonicNonNull Collection<UUID> viewers;
    private int blockWidth;
    private int blockHeight;
    private @MonotonicNonNull Location position;

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
     * Sets the players who see the block wall. The collection is not copied, see {@link BlockConfiguration}.
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
     * Sets the bottom center of the wall.
     *
     * @param position the position of the wall, which must have a world
     * @return this builder
     * @throws NullPointerException if the position is null
     */
    public T position(final Location position) {
      Preconditions.checkNotNull(position, "Position must not be null");
      this.position = position;
      return this.self();
    }

    /**
     * Sets the width of the wall in blocks.
     *
     * @param blockWidth the width in blocks, which must be positive
     * @return this builder
     */
    public T blockWidth(final int blockWidth) {
      this.blockWidth = blockWidth;
      return this.self();
    }

    /**
     * Sets the height of the wall in blocks.
     *
     * @param blockHeight the height in blocks, which must be positive
     * @return this builder
     */
    public T blockHeight(final int blockHeight) {
      this.blockHeight = blockHeight;
      return this.self();
    }

    /**
     * Builds the block configuration.
     *
     * @return the block configuration
     * @throws IllegalArgumentException if a size is not positive or the position has no world
     * @throws NullPointerException     if the viewers or the position were not set
     */
    public BlockConfiguration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");
      final Location configuredPosition = Preconditions.checkNotNull(this.position, "Position must be set");

      final World world = configuredPosition.getWorld();
      Preconditions.checkArgument(world != null, "Position must have a world");
      Preconditions.checkArgument(this.blockWidth > 0, "Block width must be positive");
      Preconditions.checkArgument(this.blockHeight > 0, "Block height must be positive");

      return new BlockConfiguration(this, configuredViewers, configuredPosition);
    }
  }
}
