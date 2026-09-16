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
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Describes a screen made of maps, for use with the map images and map results.
 *
 * <p>The screen is a grid of {@code mapBlockWidth x mapBlockHeight} maps with consecutive ids, starting at
 * {@link #getMap()} for the top left map and counting row by row. A map holds 128x128 pixels, so the screen has a
 * native resolution of {@code 128 * mapBlockWidth} by {@code 128 * mapBlockHeight} pixels. Images that are
 * smaller than the screen are centered, and images that are larger are cropped equally on every side.
 *
 * <p>The viewers collection is not copied. It is read for every frame, so a concurrent collection can be passed
 * to add or remove viewers while media is playing.
 *
 * <pre><code>
 *   final MapConfiguration configuration = MapConfiguration.builder()
 *     .map(0)
 *     .mapBlockWidth(5)
 *     .mapBlockHeight(5)
 *     .viewers(viewers)
 *     .build();
 * </code></pre>
 */
public class MapConfiguration {

  private final Collection<UUID> viewers;
  private final int map;
  private final int mapBlockWidth;
  private final int mapBlockHeight;
  private final int mapWidthResolution;
  private final int mapHeightResolution;
  private final boolean resize;

  private MapConfiguration(
    final Builder<?> builder,
    final Collection<UUID> viewers,
    final int mapWidthResolution,
    final int mapHeightResolution
  ) {
    this.viewers = viewers;
    this.map = builder.map;
    this.mapBlockWidth = builder.mapBlockWidth;
    this.mapBlockHeight = builder.mapBlockHeight;
    this.mapWidthResolution = mapWidthResolution;
    this.mapHeightResolution = mapHeightResolution;
    this.resize = builder.resize;
  }

  /**
   * Checks whether frames should be resized to the configured resolution before they are displayed.
   *
   * @return true if frames should be resized, false otherwise
   */
  public boolean shouldResize() {
    return this.resize;
  }

  /**
   * Gets the players who see the maps.
   *
   * @return the UUIDs of the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the id of the top left map of the screen.
   *
   * @return the id of the first map
   */
  public int getMap() {
    return this.map;
  }

  /**
   * Gets the width of the screen in maps.
   *
   * @return the number of maps horizontally
   */
  public int getMapBlockWidth() {
    return this.mapBlockWidth;
  }

  /**
   * Gets the height of the screen in maps.
   *
   * @return the number of maps vertically
   */
  public int getMapBlockHeight() {
    return this.mapBlockHeight;
  }

  /**
   * Gets the width in pixels that images are resized to if {@link #shouldResize()} is set. Defaults to the native
   * width of the screen.
   *
   * @return the target width in pixels
   */
  public int getMapWidthResolution() {
    return this.mapWidthResolution;
  }

  /**
   * Gets the height in pixels that images are resized to if {@link #shouldResize()} is set. Defaults to the native
   * height of the screen.
   *
   * @return the target height in pixels
   */
  public int getMapHeightResolution() {
    return this.mapHeightResolution;
  }

  /**
   * The builder returned by {@link #builder()}.
   */
  public static final class MapResultBuilder extends Builder<MapResultBuilder> {

    MapResultBuilder() {
      // created through MapConfiguration.builder()
    }

    /**
     * Returns this builder with its concrete type.
     *
     * @return this builder
     */
    @Override
    protected MapResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder for a map configuration.
   *
   * @return a new builder
   */
  public static Builder<?> builder() {
    return new MapResultBuilder();
  }

  /**
   * Builds map configurations. The map id, the grid size, and the viewers are required.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private @MonotonicNonNull Collection<UUID> viewers;
    private int map;
    private int mapBlockWidth;
    private int mapBlockHeight;
    private int mapWidthResolution;
    private int mapHeightResolution;
    private boolean resize;

    Builder() {
      this.map = -1;
    }

    /**
     * Returns this builder with its concrete type, so the setters can be chained.
     *
     * @return this builder
     */
    abstract T self();

    /**
     * Sets whether frames and images are resized to the configured resolution before they are displayed. Without
     * resizing, images smaller than the maps are centered and larger ones are cropped.
     *
     * <p>For videos, prefer resizing in the media player with its
     * {@link me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback}, which is faster because it
     * happens before any other filter runs. Images shown with
     * {@link me.brandonli.mcav.bukkit.media.image.DisplayableImage} have no player, so this option is the way to fit
     * them to the maps.
     *
     * @param resize true to resize frames and images, false to show them at their original size
     * @return this builder
     */
    public T resize(final boolean resize) {
      this.resize = resize;
      return this.self();
    }

    /**
     * Sets the players who see the maps. The collection is not copied, see {@link MapConfiguration}.
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
     * Sets the id of the top left map of the screen.
     *
     * @param map the id of the first map, which must be non-negative
     * @return this builder
     */
    public T map(final int map) {
      this.map = map;
      return this.self();
    }

    /**
     * Sets the width of the screen in maps.
     *
     * @param mapBlockWidth the number of maps horizontally, which must be positive
     * @return this builder
     */
    public T mapBlockWidth(final int mapBlockWidth) {
      this.mapBlockWidth = mapBlockWidth;
      return this.self();
    }

    /**
     * Sets the height of the screen in maps.
     *
     * @param mapBlockHeight the number of maps vertically, which must be positive
     * @return this builder
     */
    public T mapBlockHeight(final int mapBlockHeight) {
      this.mapBlockHeight = mapBlockHeight;
      return this.self();
    }

    /**
     * Sets the width in pixels that images are resized to. Defaults to {@code 128 * mapBlockWidth}.
     *
     * @param mapWidthResolution the target width in pixels, which must not be negative; 0 selects the default
     * @return this builder
     */
    public T mapWidthResolution(final int mapWidthResolution) {
      this.mapWidthResolution = mapWidthResolution;
      return this.self();
    }

    /**
     * Sets the height in pixels that images are resized to. Defaults to {@code 128 * mapBlockHeight}.
     *
     * @param mapHeightResolution the target height in pixels, which must not be negative; 0 selects the default
     * @return this builder
     */
    public T mapHeightResolution(final int mapHeightResolution) {
      this.mapHeightResolution = mapHeightResolution;
      return this.self();
    }

    /**
     * Builds the map configuration. The builder is not changed, so it can be reused to build more configurations.
     *
     * @return the map configuration
     * @throws IllegalArgumentException if a value is out of range
     * @throws NullPointerException     if the viewers were not set
     */
    public MapConfiguration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");

      Preconditions.checkArgument(this.map >= 0, "Map id must be set and non-negative");
      Preconditions.checkArgument(this.mapBlockWidth > 0, "Map block width must be positive");
      Preconditions.checkArgument(this.mapBlockHeight > 0, "Map block height must be positive");
      Preconditions.checkArgument(this.mapWidthResolution >= 0, "Map width resolution must not be negative");
      Preconditions.checkArgument(this.mapHeightResolution >= 0, "Map height resolution must not be negative");

      final int widthResolution = resolveResolution(this.mapWidthResolution, this.mapBlockWidth);
      final int heightResolution = resolveResolution(this.mapHeightResolution, this.mapBlockHeight);
      return new MapConfiguration(this, configuredViewers, widthResolution, heightResolution);
    }

    // a resolution of 0 stands for the native resolution of the maps
    private static int resolveResolution(final int configuredResolution, final int maps) {
      if (configuredResolution > 0) {
        return configuredResolution;
      }
      return MapLayout.MAP_SIZE * maps;
    }
  }
}
