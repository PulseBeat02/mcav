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
import org.bukkit.Location;

/**
 * Represents a configuration for block related prototypes.
 */
public class BlockConfiguration {

  private final Collection<UUID> viewers;
  private final int blockWidth;
  private final int blockHeight;
  private final Location position;

  private BlockConfiguration(final BlockConfiguration.Builder<?> builder) {
    this.viewers = builder.viewers;
    this.blockWidth = builder.blockWidth;
    this.blockHeight = builder.blockHeight;
    this.position = builder.position;
  }

  /**
   * Gets the viewers of this block configuration.
   *
   * @return the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the width of the block in blocks.
   *
   * @return the block width
   */
  public int getBlockWidth() {
    return this.blockWidth;
  }

  /**
   * Gets the height of the block in blocks.
   *
   * @return the block height
   */
  public int getBlockHeight() {
    return this.blockHeight;
  }

  /**
   * Gets the position of the block.
   *
   * @return the position
   */
  public Location getPosition() {
    return this.position;
  }

  /**
   * Block configuration builder abstraction.
   */
  public static final class BlockResultBuilder extends BlockConfiguration.Builder<BlockConfiguration.BlockResultBuilder> {

    BlockResultBuilder() {
      // no-op
    }

    @Override
    protected BlockConfiguration.BlockResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new block configuration builder.
   *
   * @return a new block configuration builder
   */
  public static BlockConfiguration.Builder<?> builder() {
    return new BlockConfiguration.BlockResultBuilder();
  }

  /**
   * Abstract builder for block configurations.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends BlockConfiguration.Builder<T>> {

    private Collection<UUID> viewers;
    private int blockWidth;
    private int blockHeight;
    private Location position;

    Builder() {
      // no-op
    }

    abstract T self();

    /**
     * Sets the viewers of this block configuration.
     *
     * @param viewers the viewers to set
     * @return the builder instance for chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the position of this block configuration.
     *
     * @param position the position to set
     * @return the builder instance for chaining
     */
    public T position(final Location position) {
      this.position = position;
      return this.self();
    }

    /**
     * Sets the width of the configuration in blocks.
     *
     * @param blockWidth the block width to set
     * @return the builder instance for chaining
     */
    public T blockWidth(final int blockWidth) {
      this.blockWidth = blockWidth;
      return this.self();
    }

    /**
     * Sets the height of the configuration in blocks.
     *
     * @param blockHeight the block height to set
     * @return the builder instance for chaining
     */
    public T blockHeight(final int blockHeight) {
      this.blockHeight = blockHeight;
      return this.self();
    }

    /**
     * Builds the block configuration.
     *
     * @return a new instance of BlockConfiguration
     */
    public BlockConfiguration build() {
      Preconditions.checkArgument(this.blockWidth > 0, "Map block width must be positive");
      Preconditions.checkArgument(this.blockHeight > 0, "Map block height must be positive");
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.position);
      return new BlockConfiguration(this);
    }
  }
}
