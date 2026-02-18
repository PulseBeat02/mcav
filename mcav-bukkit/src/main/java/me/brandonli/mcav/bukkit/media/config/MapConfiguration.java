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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Represents a configuration for map related prototypes.
 */
public class MapConfiguration {

  private final List<Integer> mapIds;
  private final Collection<UUID> viewers;
  private final int map;
  private final int mapBlockWidth;
  private final int mapBlockHeight;
  private final int mapWidthResolution;
  private final int mapHeightResolution;
  private final boolean resize;

  private MapConfiguration(final Builder<?> builder) {
    this.mapIds = new ArrayList<>();
    this.viewers = builder.viewers;
    this.map = builder.map;
    this.mapBlockWidth = builder.mapBlockWidth;
    this.mapBlockHeight = builder.mapBlockHeight;
    this.mapWidthResolution = builder.mapWidthResolution;
    this.mapHeightResolution = builder.mapHeightResolution;
    this.resize = builder.resize;
  }

  /**
   * Checks if the media should be resized to fit the map screen.
   *
   * @return true if the media should be resized, false otherwise
   */
  public boolean shouldResize() {
    return this.resize;
  }

  /**
   * Gets the list of map IDs associated with this configuration.
   *
   * @return a list of integers representing the map IDs
   */
  public List<Integer> getMapIds() {
    return this.mapIds;
  }

  /**
   * Gets the viewers of this map configuration.
   *
   * @return the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the map ID.
   *
   * @return the map ID as an integer
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Gets the width of the whole map screen in blocks.
   *
   * @return the width of the map screen in blocks
   */
  public int getMapBlockWidth() {
    return this.mapBlockWidth;
  }

  /**
   * Gets the height of the whole map screen in blocks.
   *
   * @return the height of the map screen in blocks
   */
  public int getMapBlockHeight() {
    return this.mapBlockHeight;
  }

  /**
   * Gets the width of the whole map screen in pixels.
   *
   * @return the width of the map screen in pixels
   */
  public int getMapWidthResolution() {
    return this.mapWidthResolution;
  }

  /**
   * Gets the height of the whole map screen in pixels.
   *
   * @return the height of the map screen in pixels
   */
  public int getMapHeightResolution() {
    return this.mapHeightResolution;
  }

  /**
   * Map configuration builder abstraction.
   */
  public static final class MapResultBuilder extends Builder<MapResultBuilder> {

    MapResultBuilder() {
      // no-op
    }

    @Override
    protected MapResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new map configuration builder.
   *
   * @return a new map configuration builder
   */
  public static Builder<?> builder() {
    return new MapResultBuilder();
  }

  /**
   * Abstract builder for map configurations.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private int map;
    private int mapBlockWidth;
    private int mapBlockHeight;
    private int mapWidthResolution;
    private int mapHeightResolution;
    private boolean resize;

    Builder() {
      // no-op
    }

    abstract T self();

    /**
     * Sets whether to resize the media to fit the map screen.
     * @param resize true to resize the media, false to keep original dimensions
     * @deprecated You should be using the media player to handle resizing
     * @see me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback for resizing media using the player
     * @return the builder instance for chaining
     */
    @Deprecated
    public T resize(final boolean resize) {
      this.resize = resize;
      return this.self();
    }

    /**
     * Sets the viewers of this map configuration.
     *
     * @param viewers the viewers to set
     * @return the builder instance for chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the map ID of this configuration.
     *
     * @param map the map ID, which must be non-negative
     * @return the builder instance for method chaining
     */
    public T map(final int map) {
      this.map = map;
      return this.self();
    }

    /**
     * Sets the map screen block width of this configuration.
     *
     * @param mapBlockWidth the block width of the map screen
     * @return the builder instance for method chaining
     */
    public T mapBlockWidth(final int mapBlockWidth) {
      this.mapBlockWidth = mapBlockWidth;
      return this.self();
    }

    /**
     * Sets the map screen block height of this configuration.
     *
     * @param mapBlockHeight the block height of the map screen
     * @return the builder instance for method chaining
     */
    public T mapBlockHeight(final int mapBlockHeight) {
      this.mapBlockHeight = mapBlockHeight;
      return this.self();
    }

    /**
     * Sets the map screen width resolution in pixels.
     *
     * @param mapWidthResolution the pixel width resolution of the map screen
     * @return the builder instance for chaining additional configuration
     */
    public T mapWidthResolution(final int mapWidthResolution) {
      this.mapWidthResolution = mapWidthResolution;
      return this.self();
    }

    /**
     * Sets the map screen height resolution in pixels.
     *
     * @param mapHeightResolution the pixel height resolution of the map screen
     * @return the builder instance for chaining additional configuration
     */
    public T mapHeightResolution(final int mapHeightResolution) {
      this.mapHeightResolution = mapHeightResolution;
      return this.self();
    }

    /**
     * Builds the map configuration.
     *
     * @return a new instance of MapConfiguration
     */
    public MapConfiguration build() {
      Preconditions.checkArgument(this.map >= 0, "Map ID must be non-negative");
      Preconditions.checkArgument(this.mapBlockWidth > 0, "Map block width must be positive");
      Preconditions.checkArgument(this.mapBlockHeight > 0, "Map block height must be positive");
      Preconditions.checkNotNull(this.viewers);
      this.mapWidthResolution = this.mapWidthResolution == 0 ? 128 * this.mapBlockWidth : this.mapWidthResolution;
      this.mapHeightResolution = this.mapHeightResolution == 0 ? 128 * this.mapBlockHeight : this.mapHeightResolution;
      return new MapConfiguration(this);
    }
  }
}
