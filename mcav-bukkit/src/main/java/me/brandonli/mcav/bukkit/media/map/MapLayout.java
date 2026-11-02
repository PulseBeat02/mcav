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
package me.brandonli.mcav.bukkit.media.map;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Describes how an image is placed onto a grid of maps.
 *
 * <p>The grid consists of {@code columns x rows} maps with consecutive ids, numbered row by row starting at the
 * top left map. The image is centered on the grid: if it is smaller than the grid, the maps around it are left
 * untouched, and if it is larger, it is cropped equally on every side.
 *
 * <p>Images are passed as map palette indices, one byte per pixel, laid out row by row. Instances are immutable
 * and thread-safe.
 *
 * <p>Odd centering differences use integer division toward zero, so opposite margins or crop amounts may
 * differ by one pixel. Extracted patches own copied color arrays and do not retain the source image array.
 *
 * @since 1.0.0
 */
public final class MapLayout {

  private static final String IMAGE_NULL_MESSAGE = "Image must not be null!";

  private static final String IMAGE_SIZE_MISMATCH_MESSAGE = "Image length %s does not match the layout's %s pixels!";

  /**
   * The width and height of a single map in pixels.
   *
   * @since 1.0.0
   */
  public static final int MAP_SIZE = 128;

  /**
   * An estimate of the per-patch overhead of a map data packet in bytes. It covers the packet id, the map id, the
   * scale, the lock flag, the empty decoration list, and the rectangle header.
   *
   * @since 1.0.0
   */
  public static final int PATCH_OVERHEAD = 16;

  private static final int CENTERING_DIVISOR = 2;

  private final int startMapId;
  private final int columns;
  private final int rows;
  private final int imageWidth;
  private final int imageHeight;
  private final int offsetX;
  private final int offsetY;
  private final MapRegion[] regions;

  /**
   * Creates a new layout.
   *
   * @param startMapId  the nonnegative id of the top left map
   * @param columns     the strictly positive number of maps horizontally
   * @param rows        the strictly positive number of maps vertically
   * @param imageWidth  the strictly positive width of the image in pixels
   * @param imageHeight the strictly positive height of the image in pixels
   * @throws IllegalArgumentException if any argument is out of range, the map count, last map id or the size of the
   *                                  grid in pixels would exceed {@link Integer#MAX_VALUE}, or the image has more
   *                                  than {@link Integer#MAX_VALUE} pixels
   * @since 1.0.0
   */
  public MapLayout(final int startMapId, final int columns, final int rows, final int imageWidth, final int imageHeight) {
    validateDimensions(startMapId, columns, rows, imageWidth, imageHeight);

    this.startMapId = startMapId;
    this.columns = columns;
    this.rows = rows;
    this.imageWidth = imageWidth;
    this.imageHeight = imageHeight;

    final int gridWidth = columns * MAP_SIZE;
    final int gridHeight = rows * MAP_SIZE;
    this.offsetX = (gridWidth - imageWidth) / CENTERING_DIVISOR;
    this.offsetY = (gridHeight - imageHeight) / CENTERING_DIVISOR;
    this.regions = this.computeRegions();
  }

  private static void validateDimensions(
    final int startMapId,
    final int columns,
    final int rows,
    final int imageWidth,
    final int imageHeight
  ) {
    Preconditions.checkArgument(startMapId >= 0, "Map id %s must not be negative!", startMapId);
    Preconditions.checkArgument(columns > 0 && rows > 0, "Map grid of %sx%s maps must be at least 1x1!", columns, rows);
    Preconditions.checkArgument(imageWidth > 0 && imageHeight > 0, "Image of %sx%s pixels must not be empty!", imageWidth, imageHeight);

    final long longMapCount = (long) columns * rows;
    Preconditions.checkArgument(longMapCount <= Integer.MAX_VALUE, "Map grid of %sx%s maps has too many maps!", columns, rows);

    final long lastMapId = startMapId + longMapCount - 1;
    Preconditions.checkArgument(lastMapId <= Integer.MAX_VALUE, "Map ids %s to %s exceed the largest map id!", startMapId, lastMapId);

    final long longGridWidth = (long) columns * MAP_SIZE;
    final long longGridHeight = (long) rows * MAP_SIZE;
    final long largestGridSide = Math.max(longGridWidth, longGridHeight);
    Preconditions.checkArgument(largestGridSide <= Integer.MAX_VALUE, "Map grid of %sx%s maps is too large!", columns, rows);

    final long pixelCount = (long) imageWidth * imageHeight;
    Preconditions.checkArgument(pixelCount <= Integer.MAX_VALUE, "Image of %sx%s pixels is too large!", imageWidth, imageHeight);
  }

  private MapRegion[] computeRegions(@UnderInitialization MapLayout this) {
    final int mapCount = this.columns * this.rows;
    final MapRegion[] computed = new MapRegion[mapCount];
    for (int index = 0; index < mapCount; index++) {
      computed[index] = this.computeRegion(index);
    }

    return computed;
  }

  private MapRegion computeRegion(@UnderInitialization MapLayout this, final int index) {
    final int column = index % this.columns;
    final int row = index / this.columns;
    final int gridX = column * MAP_SIZE;
    final int gridY = row * MAP_SIZE;

    final int localX = Math.max(0, this.offsetX - gridX);
    final int localY = Math.max(0, this.offsetY - gridY);
    final int sourceX = gridX + localX - this.offsetX;
    final int sourceY = gridY + localY - this.offsetY;

    final int remainingMapWidth = MAP_SIZE - localX;
    final int remainingMapHeight = MAP_SIZE - localY;
    final int remainingImageWidth = this.imageWidth - sourceX;
    final int remainingImageHeight = this.imageHeight - sourceY;
    final int coveredWidth = Math.min(remainingMapWidth, remainingImageWidth);
    final int coveredHeight = Math.min(remainingMapHeight, remainingImageHeight);
    final int width = Math.max(0, coveredWidth);
    final int height = Math.max(0, coveredHeight);
    return MapRegion.of(localX, localY, width, height, sourceX, sourceY);
  }

  /**
   * Returns the id of the map at the specified index.
   *
   * @param index the index of the map, counted row by row from the top left
   * @return the map id
   * @throws IndexOutOfBoundsException if the map index is outside of the grid
   * @since 1.0.0
   */
  public int getMapId(final int index) {
    Preconditions.checkElementIndex(index, this.regions.length, "Map index");

    return this.startMapId + index;
  }

  /**
   * Returns the part of the map at the specified index that is covered by the image.
   *
   * @param index the index of the map, counted row by row from the top left
   * @return the covered region, which is empty if the image does not reach this map
   * @throws IndexOutOfBoundsException if the map index is outside of the grid
   * @since 1.0.0
   */
  public MapRegion getRegion(final int index) {
    Preconditions.checkElementIndex(index, this.regions.length, "Map index");

    return this.regions[index];
  }

  /**
   * Returns whether this layout was created with exactly the specified grid and image dimensions.
   *
   * @param startMapId  the id of the top left map
   * @param columns     the number of maps horizontally
   * @param rows        the number of maps vertically
   * @param imageWidth  the width of the image in pixels
   * @param imageHeight the height of the image in pixels
   * @return true if the layout is identical
   * @since 1.0.0
   */
  public boolean matches(final int startMapId, final int columns, final int rows, final int imageWidth, final int imageHeight) {
    final boolean sameGrid = this.startMapId == startMapId && this.columns == columns && this.rows == rows;
    final boolean sameImage = this.imageWidth == imageWidth && this.imageHeight == imageHeight;
    return sameGrid && sameImage;
  }

  /**
   * Copies a rectangle of the image into a patch for the map at the specified index. The rectangle is given in
   * map coordinates and must lie inside the region of that map.
   *
   * @param image  the image as map palette indices, laid out row by row
   * @param index  the index of the map
   * @param left   the x coordinate of the rectangle inside the map
   * @param top    the y coordinate of the rectangle inside the map
   * @param width  the width of the rectangle
   * @param height the height of the rectangle
   * @return the patch containing the colors of the rectangle
   * @throws IndexOutOfBoundsException if the map index is outside of the grid
   * @throws IllegalArgumentException  if the image size does not match the layout, or the rectangle is empty or
   *                                   not inside the region of the map
   * @throws NullPointerException      if {@code image} is null
   * @since 1.0.0
   */
  public MapTilePatch extractPatch(final byte[] image, final int index, final int left, final int top, final int width, final int height) {
    Preconditions.checkNotNull(image, IMAGE_NULL_MESSAGE);
    Preconditions.checkElementIndex(index, this.regions.length, "Map index");

    final int expectedLength = this.imageWidth * this.imageHeight;
    Preconditions.checkArgument(image.length == expectedLength, IMAGE_SIZE_MISMATCH_MESSAGE, image.length, expectedLength);

    final MapRegion region = this.regions[index];
    this.checkInsideRegion(region, index, left, top, width, height);

    final int sourceX = region.getSourceX() + (left - region.getLocalX());
    final int sourceY = region.getSourceY() + (top - region.getLocalY());
    final byte[] colors = new byte[width * height];
    for (int row = 0; row < height; row++) {
      final int sourceIndex = (sourceY + row) * this.imageWidth + sourceX;
      final int targetIndex = row * width;
      System.arraycopy(image, sourceIndex, colors, targetIndex, width);
    }

    final int mapId = this.getMapId(index);
    return MapTilePatch.of(mapId, left, top, width, height, colors);
  }

  private void checkInsideRegion(
    final MapRegion region,
    final int index,
    final int left,
    final int top,
    final int width,
    final int height
  ) {
    final int regionX = region.getLocalX();
    final int regionY = region.getLocalY();
    final int regionWidth = region.getWidth();
    final int regionHeight = region.getHeight();

    final boolean insideHorizontally = left >= regionX && width > 0 && width <= regionX + regionWidth - left;
    final boolean insideVertically = top >= regionY && height > 0 && height <= regionY + regionHeight - top;
    Preconditions.checkArgument(
      insideHorizontally && insideVertically,
      "Rectangle of %sx%s pixels at (%s, %s) in map %s is outside its region!",
      width,
      height,
      left,
      top,
      index
    );
  }

  /**
   * Creates one patch for every map covered by the image, each containing the complete covered region.
   *
   * @param image the image as map palette indices, laid out row by row
   * @return the patches for the entire image
   * @throws IllegalArgumentException if the image size does not match the layout
   * @throws NullPointerException     if {@code image} is null
   * @since 1.0.0
   */
  public List<MapTilePatch> extractAll(final byte[] image) {
    Preconditions.checkNotNull(image, IMAGE_NULL_MESSAGE);

    final int expectedLength = this.imageWidth * this.imageHeight;
    Preconditions.checkArgument(image.length == expectedLength, IMAGE_SIZE_MISMATCH_MESSAGE, image.length, expectedLength);

    final List<MapTilePatch> patches = new ArrayList<>(this.regions.length);
    for (int index = 0; index < this.regions.length; index++) {
      final MapRegion region = this.regions[index];
      if (region.isEmpty()) {
        continue;
      }

      final int localX = region.getLocalX();
      final int localY = region.getLocalY();
      final int width = region.getWidth();
      final int height = region.getHeight();
      final MapTilePatch patch = this.extractPatch(image, index, localX, localY, width, height);
      patches.add(patch);
    }

    return patches;
  }

  /**
   * Returns the number of maps in the grid.
   *
   * @return the number of maps
   * @since 1.0.0
   */
  public int getMapCount() {
    return this.regions.length;
  }

  /**
   * Returns the width of the image in pixels.
   *
   * @return the image width
   * @since 1.0.0
   */
  public int getImageWidth() {
    return this.imageWidth;
  }

  /**
   * Returns the height of the image in pixels.
   *
   * @return the image height
   * @since 1.0.0
   */
  public int getImageHeight() {
    return this.imageHeight;
  }
}
