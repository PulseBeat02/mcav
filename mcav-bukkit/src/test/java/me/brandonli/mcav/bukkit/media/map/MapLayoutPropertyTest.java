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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of {@link MapLayout}, {@link MapRegion} and the patches a layout cuts out of an image, for every grid and
 * image size rather than the few the examples use.
 */
final class MapLayoutPropertyTest {

  private static final String SEED = "20260925";
  private static final int MAX_SIDE_IN_MAPS = 6;
  private static final int MAX_IMAGE_SIDE = MAX_SIDE_IN_MAPS * MapLayout.MAP_SIZE + 90;

  /**
   * Creates integers in a range from a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones
   * configured from it, so one base configured twice would hand every user the last range.
   */
  private static Arbitrary<Integer> between(final int min, final int max) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(min, max);
  }

  @Provide
  Arbitrary<MapGrid> grids() {
    final Arbitrary<Integer> smallIds = between(0, 100);
    final Arbitrary<Integer> largeIds = between(0, 1 << 30);
    final Arbitrary<Integer> startMapIds = Arbitraries.oneOf(smallIds, largeIds);
    final Arbitrary<Integer> columns = between(1, MAX_SIDE_IN_MAPS);
    final Arbitrary<Integer> rows = between(1, MAX_SIDE_IN_MAPS);
    final Arbitrary<Integer> widths = between(1, MAX_IMAGE_SIDE);
    final Arbitrary<Integer> heights = between(1, MAX_IMAGE_SIDE);
    final Combinators.Combinator5<Integer, Integer, Integer, Integer, Integer> grids = Combinators.combine(
      startMapIds,
      columns,
      rows,
      widths,
      heights
    );
    return grids.as(MapGrid::new);
  }

  @Property(seed = SEED)
  void everyMapIdLiesInTheRangeOfItsLayout(@ForAll("grids") final MapGrid grid) {
    final MapLayout layout = grid.createLayout();
    final int mapCount = layout.getMapCount();
    final long firstId = grid.getStartMapId();
    final long lastId = firstId + mapCount - 1;
    final int cells = grid.getColumns() * grid.getRows();

    assertEquals(cells, mapCount, "one map per cell of the grid");
    for (int index = 0; index < mapCount; index++) {
      final long mapId = layout.getMapId(index);
      assertEquals(firstId + index, mapId, "maps are numbered consecutively");
    }
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(mapCount));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(-1));

    final byte[] image = grid.createImage();
    final List<MapTilePatch> patches = layout.extractAll(image);
    for (final MapTilePatch patch : patches) {
      final long mapId = patch.getMapId();
      final boolean inRange = mapId >= firstId && mapId <= lastId;
      assertTrue(inRange, () -> "patch for map " + mapId + " outside of " + firstId + ".." + lastId);
    }
  }

  @Property(seed = SEED)
  void theMapsShowEveryVisiblePixelOfTheImageExactlyOnceAndCentered(@ForAll("grids") final MapGrid grid) {
    final MapLayout layout = grid.createLayout();
    final byte[] image = grid.createImage();
    final List<MapTilePatch> patches = layout.extractAll(image);
    final ImageCoverage coverage = new ImageCoverage(grid, layout, image);

    for (final MapTilePatch patch : patches) {
      coverage.add(patch);
    }

    final int width = grid.getImageWidth();
    final int height = grid.getImageHeight();
    final int visibleWidth = Math.min(width, grid.getGridWidth());
    final int visibleHeight = Math.min(height, grid.getGridHeight());
    final int coveredPixels = coverage.countCoveredOnce();
    assertEquals(visibleWidth * visibleHeight, coveredPixels, "every pixel that fits on the grid is shown once");
    coverage.assertCentered();
  }

  @Property(seed = SEED)
  void rejectsGridsWhoseIdsWouldPassTheLargestMapId(
    @ForAll @IntRange(min = 1, max = 64) final int columns,
    @ForAll @IntRange(min = 1, max = 64) final int rows,
    @ForAll @IntRange(min = 0, max = 4095) final int overshoot
  ) {
    final int mapCount = columns * rows;
    // a single map may start at every id, so there is nothing to refuse
    Assume.that(mapCount > 1);
    final long lastAllowedStart = (long) Integer.MAX_VALUE - mapCount + 1;
    final long overshootingStart = Math.min(Integer.MAX_VALUE, lastAllowedStart + 1 + overshoot);
    final int tooLate = (int) overshootingStart;
    final int fits = (int) lastAllowedStart;

    assertThrows(IllegalArgumentException.class, () -> new MapLayout(tooLate, columns, rows, 1, 1));
    final MapLayout layout = new MapLayout(fits, columns, rows, 1, 1);
    final int lastId = layout.getMapId(mapCount - 1);
    assertEquals(Integer.MAX_VALUE, lastId, "the last map may have the largest id");
  }

  /**
   * A grid of maps with an image on it; the image may be smaller than the grid, larger, or larger in one direction.
   */
  static final class MapGrid {

    private final int startMapId;
    private final int columns;
    private final int rows;
    private final int imageWidth;
    private final int imageHeight;

    MapGrid(final int startMapId, final int columns, final int rows, final int imageWidth, final int imageHeight) {
      this.startMapId = startMapId;
      this.columns = columns;
      this.rows = rows;
      this.imageWidth = imageWidth;
      this.imageHeight = imageHeight;
    }

    MapLayout createLayout() {
      return new MapLayout(this.startMapId, this.columns, this.rows, this.imageWidth, this.imageHeight);
    }

    /**
     * Fills an image so that neighboring pixels have different colors, cycling through the 256 a byte holds.
     */
    byte[] createImage() {
      final byte[] image = new byte[this.imageWidth * this.imageHeight];
      for (int index = 0; index < image.length; index++) {
        final int row = index / this.imageWidth;
        image[index] = (byte) (index * 31 + row * 7);
      }
      return image;
    }

    int getStartMapId() {
      return this.startMapId;
    }

    int getColumns() {
      return this.columns;
    }

    int getRows() {
      return this.rows;
    }

    int getImageWidth() {
      return this.imageWidth;
    }

    int getImageHeight() {
      return this.imageHeight;
    }

    int getGridWidth() {
      return this.columns * MapLayout.MAP_SIZE;
    }

    int getGridHeight() {
      return this.rows * MapLayout.MAP_SIZE;
    }

    @Override
    public String toString() {
      return (
        "maps " + this.columns + "x" + this.rows + " from id " + this.startMapId + ", image " + this.imageWidth + "x" + this.imageHeight
      );
    }
  }

  /**
   * Which pixels of the image the patches show, checked against the image as the patches are added. Every patch must
   * show the image at the same position on the grid, so the offset of the image is learned from the first patch.
   */
  private static final class ImageCoverage {

    private final MapGrid grid;
    private final MapLayout layout;
    private final byte[] image;
    private final int[] counts;
    private boolean offsetKnown;
    private int offsetX;
    private int offsetY;

    ImageCoverage(final MapGrid grid, final MapLayout layout, final byte[] image) {
      this.grid = grid;
      this.layout = layout;
      this.image = image;
      this.counts = new int[image.length];
    }

    void add(final MapTilePatch patch) {
      final int mapId = patch.getMapId();
      final int index = mapId - this.grid.getStartMapId();
      final MapRegion region = this.layout.getRegion(index);
      assertInsideRegion(patch, region);

      final int columns = this.grid.getColumns();
      final int x = patch.getX();
      final int y = patch.getY();
      final int gridX = (index % columns) * MapLayout.MAP_SIZE + x;
      final int gridY = (index / columns) * MapLayout.MAP_SIZE + y;
      final int sourceX = region.getSourceX() + x - region.getLocalX();
      final int sourceY = region.getSourceY() + y - region.getLocalY();
      this.checkOffset(gridX - sourceX, gridY - sourceY);
      this.mark(patch, sourceX, sourceY);
    }

    private void checkOffset(final int patchOffsetX, final int patchOffsetY) {
      if (!this.offsetKnown) {
        this.offsetKnown = true;
        this.offsetX = patchOffsetX;
        this.offsetY = patchOffsetY;
      }
      assertEquals(this.offsetX, patchOffsetX, "every map shows the image at the same horizontal position");
      assertEquals(this.offsetY, patchOffsetY, "every map shows the image at the same vertical position");
    }

    private void mark(final MapTilePatch patch, final int sourceX, final int sourceY) {
      final byte[] colors = patch.getColors();
      final int width = patch.getWidth();
      final int height = patch.getHeight();
      final int imageWidth = this.grid.getImageWidth();
      for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
          final int imageIndex = (sourceY + row) * imageWidth + sourceX + column;
          final byte expected = this.image[imageIndex];
          final byte actual = colors[row * width + column];
          assertEquals(expected, actual, "the map shows the pixel of the image at its position");
          this.counts[imageIndex]++;
        }
      }
    }

    int countCoveredOnce() {
      int covered = 0;
      for (final int count : this.counts) {
        assertTrue(count <= 1, "no pixel of the image is shown twice");
        covered += count;
      }
      return covered;
    }

    /**
     * Asserts that the image is centered: the grid pixels left over before and after it, or the image pixels cut off
     * before and after the grid, differ by at most one on each axis.
     */
    void assertCentered() {
      if (!this.offsetKnown) {
        return;
      }
      final int gridWidth = this.grid.getGridWidth();
      final int gridHeight = this.grid.getGridHeight();
      final int imageWidth = this.grid.getImageWidth();
      final int imageHeight = this.grid.getImageHeight();
      assertBalanced(this.offsetX, gridWidth - imageWidth - this.offsetX, "horizontally");
      assertBalanced(this.offsetY, gridHeight - imageHeight - this.offsetY, "vertically");
    }

    private static void assertBalanced(final int before, final int after, final String axis) {
      final int difference = Math.abs(before - after);
      assertTrue(difference <= 1, () -> "image centered " + axis + ": " + before + " before, " + after + " after");
    }

    private static void assertInsideRegion(final MapTilePatch patch, final MapRegion region) {
      final int x = patch.getX();
      final int y = patch.getY();
      final int right = x + patch.getWidth();
      final int bottom = y + patch.getHeight();
      final int regionX = region.getLocalX();
      final int regionY = region.getLocalY();
      final int regionRight = regionX + region.getWidth();
      final int regionBottom = regionY + region.getHeight();
      final boolean inside = x >= regionX && y >= regionY && right <= regionRight && bottom <= regionBottom;
      final boolean insideMap = right <= MapLayout.MAP_SIZE && bottom <= MapLayout.MAP_SIZE;
      assertTrue(inside && insideMap, "patch inside the covered region of its map");
    }
  }
}
