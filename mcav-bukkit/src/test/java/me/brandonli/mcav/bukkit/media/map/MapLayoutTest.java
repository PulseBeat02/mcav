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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MapLayout}.
 */
final class MapLayoutTest {

  static byte[] createImage(final int width, final int height) {
    final byte[] image = new byte[width * height];
    for (int index = 0; index < image.length; index++) {
      image[index] = (byte) (index % 251);
    }
    return image;
  }

  static byte[] copyRectangle(final byte[] image, final int imageWidth, final int x, final int y, final int width, final int height) {
    final byte[] colors = new byte[width * height];
    for (int row = 0; row < height; row++) {
      System.arraycopy(image, (y + row) * imageWidth + x, colors, row * width, width);
    }
    return colors;
  }

  private static void assertRegion(
    final MapRegion region,
    final int localX,
    final int localY,
    final int width,
    final int height,
    final int sourceX,
    final int sourceY
  ) {
    final int actualLocalX = region.getLocalX();
    final int actualLocalY = region.getLocalY();
    final int actualWidth = region.getWidth();
    final int actualHeight = region.getHeight();
    final int actualSourceX = region.getSourceX();
    final int actualSourceY = region.getSourceY();

    assertEquals(localX, actualLocalX, "local x");
    assertEquals(localY, actualLocalY, "local y");
    assertEquals(width, actualWidth, "width");
    assertEquals(height, actualHeight, "height");
    assertEquals(sourceX, actualSourceX, "source x");
    assertEquals(sourceY, actualSourceY, "source y");
  }

  @Test
  void describesMapsAndPatchOverhead() {
    assertEquals(128, MapLayout.MAP_SIZE);
    assertEquals(16, MapLayout.PATCH_OVERHEAD);
  }

  @Test
  void numbersMapsRowByRowFromTheStartId() {
    final MapLayout layout = new MapLayout(10, 3, 2, 384, 256);
    final int mapCount = layout.getMapCount();
    final int firstId = layout.getMapId(0);
    final int lastId = layout.getMapId(5);
    final int imageWidth = layout.getImageWidth();
    final int imageHeight = layout.getImageHeight();
    final MapRegion secondRowSecondColumn = layout.getRegion(4);

    assertEquals(6, mapCount);
    assertEquals(10, firstId);
    assertEquals(15, lastId);
    assertEquals(384, imageWidth);
    assertEquals(256, imageHeight);
    assertRegion(secondRowSecondColumn, 0, 0, 128, 128, 128, 128);
  }

  @Test
  void centersImagesThatAreSmallerThanTheGrid() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final MapRegion region = layout.getRegion(0);

    assertRegion(region, 14, 39, 100, 50, 0, 0);
  }

  @Test
  void leavesMapsTheImageDoesNotReachEmpty() {
    final MapLayout layout = new MapLayout(0, 3, 1, 128, 64);
    final MapRegion left = layout.getRegion(0);
    final MapRegion middle = layout.getRegion(1);
    final MapRegion right = layout.getRegion(2);
    final boolean leftEmpty = left.isEmpty();
    final boolean middleEmpty = middle.isEmpty();
    final boolean rightEmpty = right.isEmpty();

    assertTrue(leftEmpty);
    assertFalse(middleEmpty);
    assertTrue(rightEmpty);
    assertRegion(middle, 0, 32, 128, 64, 0, 0);
  }

  @Test
  void cropsImagesThatAreLargerThanTheGridEquallyOnEverySide() {
    final MapLayout layout = new MapLayout(0, 1, 1, 200, 150);
    final MapRegion region = layout.getRegion(0);

    assertRegion(region, 0, 0, 128, 128, 36, 11);
  }

  @Test
  void splitsImagesThatSpanSeveralMapsPartially() {
    final MapLayout layout = new MapLayout(0, 2, 1, 200, 100);
    final MapRegion left = layout.getRegion(0);
    final MapRegion right = layout.getRegion(1);

    assertRegion(left, 28, 14, 100, 100, 0, 0);
    assertRegion(right, 0, 14, 100, 100, 100, 0);
  }

  @Test
  void splitsImagesThatSpanSeveralMapsVertically() {
    final MapLayout layout = new MapLayout(0, 1, 2, 128, 200);
    final MapRegion top = layout.getRegion(0);
    final MapRegion bottom = layout.getRegion(1);

    assertRegion(top, 0, 28, 128, 100, 0, 0);
    assertRegion(bottom, 0, 0, 128, 100, 0, 100);
  }

  @Test
  void extractsRectanglesThatEndExactlyAtTheEdgeOfTheRegion() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final byte[] image = createImage(100, 50);
    final MapTilePatch patch = layout.extractPatch(image, 0, 20, 45, 94, 44);
    final byte[] expected = copyRectangle(image, 100, 6, 6, 94, 44);
    final byte[] colors = patch.getColors();
    final int width = patch.getWidth();
    final int height = patch.getHeight();

    assertEquals(94, width, "a rectangle may end exactly at the edge of the region");
    assertEquals(44, height);
    assertArrayEquals(expected, colors);
  }

  @Test
  void acceptsImagesWithTheLargestPossiblePixelCount() {
    final MapLayout layout = new MapLayout(0, 1, 1, 1, Integer.MAX_VALUE);
    final int imageHeight = layout.getImageHeight();
    final int mapCount = layout.getMapCount();

    assertEquals(Integer.MAX_VALUE, imageHeight, "the largest pixel count is still accepted");
    assertEquals(1, mapCount);
  }

  @Test
  void rejectsInvalidDimensions() {
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(-1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 1, 1, 1, 0));
  }

  @Test
  void rejectsGridsWhoseMapIdsOrPixelCountsOverflow() {
    final MapLayout lastIds = new MapLayout(Integer.MAX_VALUE - 1, 2, 1, 1, 1);
    final int lastId = lastIds.getMapId(1);

    assertEquals(Integer.MAX_VALUE, lastId);
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(Integer.MAX_VALUE, 2, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(1, 65_536, 65_536, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 16_777_216, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 1, 16_777_216, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new MapLayout(0, 1, 1, 65_536, 65_536));
  }

  @Test
  void extractsRectanglesThatFillTheWholeRegion() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final byte[] image = createImage(100, 50);
    final MapTilePatch patch = layout.extractPatch(image, 0, 14, 39, 100, 50);
    final byte[] colors = patch.getColors();

    assertArrayEquals(image, colors);
  }

  @Test
  void rejectsRectanglesThatAreNotInsideTheRegionOfTheMap() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final byte[] image = createImage(100, 50);
    final byte[] wrongSize = new byte[10];

    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 13, 39, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 39, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 39, 101, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 114, 39, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, Integer.MAX_VALUE, 39, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 38, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 39, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 39, 1, 51));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 89, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 39, -1, -1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(wrongSize, 0, 14, 39, 1, 1));
  }

  @Test
  void rejectsMapIndicesOutsideOfTheGrid() {
    final MapLayout layout = new MapLayout(0, 2, 1, 256, 128);
    final byte[] image = new byte[256 * 128];

    assertThrows(IndexOutOfBoundsException.class, () -> layout.getRegion(2));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getRegion(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(2));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.extractPatch(image, 2, 0, 0, 1, 1));
  }

  @Test
  void matchesOnlyTheExactGridAndImage() {
    final MapLayout layout = new MapLayout(5, 2, 3, 200, 300);
    final boolean same = layout.matches(5, 2, 3, 200, 300);
    final boolean otherStart = layout.matches(6, 2, 3, 200, 300);
    final boolean otherColumns = layout.matches(5, 3, 3, 200, 300);
    final boolean otherRows = layout.matches(5, 2, 4, 200, 300);
    final boolean otherWidth = layout.matches(5, 2, 3, 201, 300);
    final boolean otherHeight = layout.matches(5, 2, 3, 200, 301);

    assertTrue(same);
    assertFalse(otherStart);
    assertFalse(otherColumns);
    assertFalse(otherRows);
    assertFalse(otherWidth);
    assertFalse(otherHeight);
  }

  @Test
  void extractsRectanglesInMapCoordinates() {
    final MapLayout layout = new MapLayout(3, 1, 1, 100, 50);
    final byte[] image = createImage(100, 50);
    final MapTilePatch patch = layout.extractPatch(image, 0, 20, 40, 3, 2);
    final byte[] expected = copyRectangle(image, 100, 6, 1, 3, 2);
    final byte[] colors = patch.getColors();
    final int mapId = patch.getMapId();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();

    assertArrayEquals(expected, colors);
    assertEquals(3, mapId);
    assertEquals(20, x);
    assertEquals(40, y);
    assertEquals(3, width);
    assertEquals(2, height);
    assertThrows(NullPointerException.class, () -> layout.extractPatch(null, 0, 20, 40, 3, 2));
  }

  @Test
  void extractsOnePatchPerCoveredMap() {
    final MapLayout layout = new MapLayout(7, 3, 1, 128, 64);
    final byte[] image = createImage(128, 64);
    final List<MapTilePatch> patches = layout.extractAll(image);
    final int patchCount = patches.size();
    final MapTilePatch patch = patches.getFirst();
    final int mapId = patch.getMapId();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();

    assertEquals(1, patchCount);
    assertEquals(8, mapId);
    assertEquals(0, x);
    assertEquals(32, y);
    assertEquals(128, width);
    assertEquals(64, height);
    assertArrayEquals(image, colors);
  }

  @Test
  void extractsEveryMapOfAFullGrid() {
    final MapLayout layout = new MapLayout(0, 2, 2, 256, 256);
    final byte[] image = createImage(256, 256);
    final List<MapTilePatch> patches = layout.extractAll(image);
    final int patchCount = patches.size();
    final MapTilePatch lastPatch = patches.getLast();
    final byte[] lastColors = lastPatch.getColors();
    final byte[] expectedLastColors = copyRectangle(image, 256, 128, 128, 128, 128);
    final int lastId = lastPatch.getMapId();

    assertEquals(4, patchCount);
    assertEquals(3, lastId);
    assertArrayEquals(expectedLastColors, lastColors);
  }

  @Test
  void rejectsImagesOfTheWrongSize() {
    final MapLayout layout = new MapLayout(0, 1, 1, 10, 10);
    final byte[] tooSmall = new byte[99];

    assertThrows(IllegalArgumentException.class, () -> layout.extractAll(tooSmall));
    assertThrows(NullPointerException.class, () -> layout.extractAll(null));
  }
}
