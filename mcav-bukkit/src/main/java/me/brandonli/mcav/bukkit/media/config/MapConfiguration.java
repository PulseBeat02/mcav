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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;

/**
 * Represents the configuration for a map, including its ID, dimensions, resolution,
 * and associated viewers. This class is immutable and must be constructed using the
 * nested {@link Builder} class or its concrete subclass {@link MapResultBuilder}.
 */
public class MapConfiguration {

  private final List<Integer> mapIds;
  private final Collection<UUID> viewers;
  private final int map;
  private final int mapBlockWidth;
  private final int mapBlockHeight;
  private final int mapWidthResolution;
  private final int mapHeightResolution;

  private MapConfiguration(final Builder<?> builder) {
    this.mapIds = new ArrayList<>();
    this.viewers = builder.viewers;
    this.map = builder.map;
    this.mapBlockWidth = builder.mapBlockWidth;
    this.mapBlockHeight = builder.mapBlockHeight;
    this.mapWidthResolution = builder.mapWidthResolution;
    this.mapHeightResolution = builder.mapHeightResolution;
  }

  /**
   * Retrieves the list of map IDs configured in this instance.
   *
   * @return a list of integers representing the map IDs
   */
  public List<Integer> getMapIds() {
    return this.mapIds;
  }

  /**
   * Retrieves the collection of viewers associated with this configuration.
   * Each viewer is represented by a UUID.
   *
   * @return a collection of UUIDs representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Retrieves the map ID associated with the current configuration instance.
   *
   * @return the map ID as an integer
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Retrieves the width of an individual map block in the current configuration.
   *
   * @return the width of the map block as an integer
   */
  public int getMapBlockWidth() {
    return this.mapBlockWidth;
  }

  /**
   * Retrieves the height of the map block, which represents the vertical dimension
   * of a single block in the map configuration.
   *
   * @return the height of the map block as an integer
   */
  public int getMapBlockHeight() {
    return this.mapBlockHeight;
  }

  /**
   * Retrieves the width resolution of the map configuration.
   *
   * @return the width resolution of the map as an integer
   */
  public int getMapWidthResolution() {
    return this.mapWidthResolution;
  }

  /**
   * Retrieves the vertical resolution of the map.
   * The map height resolution represents the number of units or pixels
   * designated for the map's height configuration.
   *
   * @return the height resolution of the map as an integer
   */
  public int getMapHeightResolution() {
    return this.mapHeightResolution;
  }

  /**
   * Map Result builder class for constructing instances of {@link MapResult}.
   */
  public static final class MapResultBuilder extends Builder<MapResultBuilder> {

    @Override
    protected MapResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates and returns a new builder instance for constructing {@link MapConfiguration} objects.
   * The builder allows setting various properties such as the map ID, block dimensions, viewers,
   * and resolution parameters. This provides a flexible and readable API for configuration.
   *
   * @return a {@link Builder} instance for configuring and building {@link MapConfiguration} objects
   */
  public static Builder<?> builder() {
    return new MapResultBuilder();
  }

  /**
   * Abstract builder class for constructing instances of {@link MapResult}.
   * This class provides methods to set the properties of the map result,
   * including viewers, map ID, block dimensions, and resolution.
   *
   * @param <T> the type of the builder extending this abstract class
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private int map;
    private int mapBlockWidth;
    private int mapBlockHeight;
    private int mapWidthResolution;
    private int mapHeightResolution;

    protected abstract T self();

    /**
     * Sets the collection of viewers for the builder.
     *
     * @param viewers a collection of UUIDs representing the viewers to be set
     * @return the builder instance
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the map ID to be used.
     *
     * @param map the map ID, which must be non-negative
     * @return the builder instance for method chaining
     */
    public T map(final int map) {
      this.map = map;
      return this.self();
    }

    /**
     * Sets the block width of the map and updates the builder with this value.
     *
     * @param mapBlockWidth the block width of the map to be set
     * @return the builder instance for method chaining
     */
    public T mapBlockWidth(final int mapBlockWidth) {
      this.mapBlockWidth = mapBlockWidth;
      return this.self();
    }

    /**
     * Sets the block height of the map and updates the builder with this value.
     *
     * @param mapBlockHeight the block height of the map to be set
     * @return the builder instance for method chaining
     */
    public T mapBlockHeight(final int mapBlockHeight) {
      this.mapBlockHeight = mapBlockHeight;
      return this.self();
    }

    /**
     * Sets the resolution of the map width. The resolution determines the number of pixels
     * or units representing the width of the map.
     *
     * @param mapWidthResolution the resolution of the map width
     * @return the builder instance for chaining additional configuration
     */
    public T mapWidthResolution(final int mapWidthResolution) {
      this.mapWidthResolution = mapWidthResolution;
      return this.self();
    }

    /**
     * Sets the resolution of the map height. This resolution determines the number
     * of pixels or units representing the height of the map.
     *
     * @param mapHeightResolution the resolution of the map height to be set
     * @return the builder instance for chaining additional configuration
     */
    public T mapHeightResolution(final int mapHeightResolution) {
      this.mapHeightResolution = mapHeightResolution;
      return this.self();
    }

    /**
     * Builds and returns a {@link DitherResultStep} instance configured with the specified parameters.
     * Validates that all required fields have been properly initialized and applies default values
     * for resolution if they are not explicitly set.
     *
     * @return a new {@link DitherResultStep} instance constructed based on the configured properties of the builder
     * @throws IllegalArgumentException if any of the required parameters (e.g., map, map block width, map block height) are invalid
     * @throws NullPointerException     if the viewers collection is not set
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
