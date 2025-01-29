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
package me.brandonli.mcav.media.video.result;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.video.DitherResultStep;
import me.brandonli.mcav.utils.PacketUtils;

/**
 * Represents the result of processing a mapped video frame in a dithering operation.
 * This class is immutable and provides all necessary properties to define a mapping transformation
 * applied to video data and its subsequent processing.
 * <p>
 * MapResult holds information about the viewers, map properties, map dimensions, and resolutions.
 * It also implements the DitherResultStep interface, enabling it to process video frame data
 * and generate packets containing mapped visual data for the viewers.
 * <p>
 * The builder pattern is used to construct instances of MapResult, ensuring that all necessary
 * properties are validated and set correctly before the object is created. This design facilitates
 * safe, flexible, and reusable usage of the class in different contexts.
 */
public class MapResult implements DitherResultStep {

  private final Collection<UUID> viewers;
  private final int map;
  private final int mapBlockWidth;
  private final int mapBlockHeight;
  private final int mapWidthResolution;
  private final int mapHeightResolution;

  private MapResult(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.map = builder.map;
    this.mapBlockWidth = builder.mapBlockWidth;
    this.mapBlockHeight = builder.mapBlockHeight;
    this.mapWidthResolution = builder.mapWidthResolution;
    this.mapHeightResolution = builder.mapHeightResolution;
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
   *
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
     * Sets the map block width for the builder
     */
    public T mapBlockWidth(final int mapBlockWidth) {
      this.mapBlockWidth = mapBlockWidth;
      return this.self();
    }

    /**
     * Sets the block height of the map and updates the builder with this value.
     *
     * @param mapBlockHeight the block height of the map to be set; must be
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
    public DitherResultStep build() {
      Preconditions.checkArgument(this.map >= 0, "Map ID must be non-negative");
      Preconditions.checkArgument(this.mapBlockWidth > 0, "Map block width must be positive");
      Preconditions.checkArgument(this.mapBlockHeight > 0, "Map block height must be positive");
      Preconditions.checkNotNull(this.viewers);
      this.mapWidthResolution = this.mapWidthResolution == 0 ? 128 * this.mapBlockWidth : this.mapWidthResolution;
      this.mapHeightResolution = this.mapHeightResolution == 0 ? 128 * this.mapBlockHeight : this.mapHeightResolution;
      return new MapResult(this);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void process(final byte[] rgb, final VideoMetadata metadata) {
    final int height = rgb.length / this.mapWidthResolution;
    final int pixW = this.mapBlockWidth << 7;
    final int pixH = this.mapBlockHeight << 7;
    final int xOff = (pixW - this.mapWidthResolution) >> 1;
    final int yOff = (pixH - height) >> 1;
    final int vidHeight = rgb.length / this.mapWidthResolution;
    final int negXOff = xOff + this.mapWidthResolution;
    final int negYOff = yOff + vidHeight;
    final int xLoopMin = Math.max(0, xOff >> 7);
    final int yLoopMin = Math.max(0, yOff >> 7);
    final int xLoopMax = Math.min(this.mapWidthResolution, (int) Math.ceil(negXOff / 128.0));
    final int yLoopMax = Math.min(height, (int) Math.ceil(negYOff / 128.0));
    final WrapperPlayServerMapData[] packetArray = new WrapperPlayServerMapData[(xLoopMax - xLoopMin) * (yLoopMax - yLoopMin)];
    int arrIndex = 0;
    for (int y = yLoopMin; y < yLoopMax; y++) {
      final int relY = y << 7;
      final int topY = Math.max(0, yOff - relY);
      final int yDiff = Math.min(128 - topY, negYOff - (relY + topY));
      for (int x = xLoopMin; x < xLoopMax; x++) {
        final int relX = x << 7;
        final int topX = Math.max(0, xOff - relX);
        final int xDiff = Math.min(128 - topX, negXOff - (relX + topX));
        final int xPixMax = xDiff + topX;
        final int yPixMax = yDiff + topY;
        final byte[] mapData = new byte[xDiff * yDiff];
        for (int iy = topY; iy < yPixMax; iy++) {
          final int yPos = relY + iy;
          final int indexY = (yPos - yOff) * this.mapWidthResolution;
          for (int ix = topX; ix < xPixMax; ix++) {
            mapData[(iy - topY) * xDiff + ix - topX] = rgb[indexY + relX + ix - xOff];
          }
        }
        final int mapId = this.map + this.mapWidthResolution * y + x;
        final WrapperPlayServerMapData packet = new WrapperPlayServerMapData(
          mapId,
          (byte) 0,
          false,
          false,
          null,
          topX,
          topY,
          xDiff,
          yDiff,
          mapData
        );
        packetArray[arrIndex++] = packet;
      }
    }
    PacketUtils.sendPackets(this.viewers, packetArray);
  }
}
