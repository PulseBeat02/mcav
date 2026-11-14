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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class MapLayoutTest {

  private static final int TWO_MAP_SIZE = 256;

  private static final int SMALL_IMAGE_HEIGHT = 50;
  private static final int SMALL_IMAGE_TOP = 39;

  private static final int SHORT_IMAGE_HEIGHT = 64;
  private static final int SHORT_IMAGE_TOP = 32;

  private static final int PARTIAL_MAP_OFFSET = 28;

  private static final int PATCH_LEFT = 20;

  private static final int OVERFLOWING_DIMENSION = 65_536;

  private static final int MAP_SIZE = 128;

  static byte[] createImage(final int width, final int height) {
    final byte[] image = new byte[width * height];
    for (int index = 0; index < image.length; index++) {
      image[index] = (byte) (index % 251);
    }

    return image;
  }

  static byte[] copyRectangle(final byte[] image, final int imageWidth, final int left, final int top, final int width, final int height) {
    final byte[] colors = new byte[width * height];
    for (int row = 0; row < height; row++) {
      System.arraycopy(image, (top + row) * imageWidth + left, colors, row * width, width);
    }

    return colors;
  }

  private void assertRegion(
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
  void shouldDescribeMapsAndPatchOverhead() {
    assertEquals(MAP_SIZE, MapLayout.MAP_SIZE);
    assertEquals(16, MapLayout.PATCH_OVERHEAD);
  }

  @Test
  void shouldNumberMapsRowByRowFromTheStartId() {
    final MapLayout layout = MapLayout.of(10, 3, 2, 384, TWO_MAP_SIZE);
    final int mapCount = layout.getMapCount();
    final int firstId = layout.getMapId(0);
    final int lastId = layout.getMapId(5);
    final int imageWidth = layout.getImageWidth();
    final int imageHeight = layout.getImageHeight();
    final MapRegion secondRowSecondColumn = layout.getRegion(4);
    final MapRegion firstRowSecondColumn = layout.getRegion(1);
    final MapRegion secondRowFirstColumn = layout.getRegion(3);

    assertEquals(6, mapCount);
    assertEquals(10, firstId);
    assertEquals(15, lastId);
    assertEquals(384, imageWidth);
    assertEquals(TWO_MAP_SIZE, imageHeight);
    this.assertRegion(secondRowSecondColumn, 0, 0, MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE);
    this.assertRegion(firstRowSecondColumn, 0, 0, MAP_SIZE, MAP_SIZE, MAP_SIZE, 0);
    this.assertRegion(secondRowFirstColumn, 0, 0, MAP_SIZE, MAP_SIZE, 0, MAP_SIZE);
  }

  @Test
  void shouldCenterImagesThatAreSmallerThanTheGrid() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final MapRegion region = layout.getRegion(0);

    this.assertRegion(region, 14, SMALL_IMAGE_TOP, 100, SMALL_IMAGE_HEIGHT, 0, 0);
  }

  @Test
  void shouldLeaveMapsTheImageDoesNotReachEmpty() {
    final MapLayout layout = MapLayout.of(0, 3, 1, MAP_SIZE, SHORT_IMAGE_HEIGHT);
    final MapRegion left = layout.getRegion(0);
    final MapRegion middle = layout.getRegion(1);
    final MapRegion right = layout.getRegion(2);
    final boolean leftEmpty = left.isEmpty();
    final boolean middleEmpty = middle.isEmpty();
    final boolean rightEmpty = right.isEmpty();

    assertTrue(leftEmpty);
    assertFalse(middleEmpty);
    assertTrue(rightEmpty);
    this.assertRegion(middle, 0, SHORT_IMAGE_TOP, MAP_SIZE, SHORT_IMAGE_HEIGHT, 0, 0);
  }

  @Test
  void shouldCropImagesThatAreLargerThanTheGridEquallyOnEverySide() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 200, 150);
    final MapRegion region = layout.getRegion(0);

    this.assertRegion(region, 0, 0, MAP_SIZE, MAP_SIZE, 36, 11);
  }

  @Test
  void shouldLeaveTheOddMarginPixelOnTheRightAndBottom() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 101, 51);
    final MapRegion region = layout.getRegion(0);

    this.assertRegion(region, 13, 38, 101, 51, 0, 0);
  }

  @Test
  void shouldCropTheOddPixelFromTheRightAndBottom() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 131, 133);
    final MapRegion region = layout.getRegion(0);

    this.assertRegion(region, 0, 0, MAP_SIZE, MAP_SIZE, 1, 2);
  }

  @Test
  void shouldSplitImagesThatSpanSeveralMapsPartially() {
    final MapLayout layout = MapLayout.of(0, 2, 1, 200, 100);
    final MapRegion left = layout.getRegion(0);
    final MapRegion right = layout.getRegion(1);

    this.assertRegion(left, PARTIAL_MAP_OFFSET, 14, 100, 100, 0, 0);
    this.assertRegion(right, 0, 14, 100, 100, 100, 0);
  }

  @Test
  void shouldSplitImagesThatSpanSeveralMapsVertically() {
    final MapLayout layout = MapLayout.of(0, 1, 2, MAP_SIZE, 200);
    final MapRegion top = layout.getRegion(0);
    final MapRegion bottom = layout.getRegion(1);

    this.assertRegion(top, 0, PARTIAL_MAP_OFFSET, MAP_SIZE, 100, 0, 0);
    this.assertRegion(bottom, 0, 0, MAP_SIZE, 100, 0, 100);
  }

  @Test
  void shouldExtractRectanglesThatEndExactlyAtTheEdgeOfTheRegion() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final byte[] image = createImage(100, SMALL_IMAGE_HEIGHT);
    final MapTilePatch patch = layout.extractPatch(image, 0, PATCH_LEFT, 45, 94, 44);
    final byte[] expected = copyRectangle(image, 100, 6, 6, 94, 44);
    final byte[] colors = patch.getColors();
    final int width = patch.getWidth();
    final int height = patch.getHeight();

    assertEquals(94, width, "a rectangle may end exactly at the edge of the region");
    assertEquals(44, height);
    assertArrayEquals(expected, colors);
  }

  @Test
  void shouldKeepTheColorsOfExtractedPatchesWhenTheImageChanges() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final byte[] image = createImage(MAP_SIZE, MAP_SIZE);
    final MapTilePatch patch = layout.extractPatch(image, 0, 0, 0, MAP_SIZE, MAP_SIZE);
    final List<MapTilePatch> patches = layout.extractAll(image);
    Arrays.fill(image, (byte) 0);
    final byte[] expected = createImage(MAP_SIZE, MAP_SIZE);
    final byte[] colors = patch.getColors();
    final MapTilePatch regionPatch = patches.getFirst();
    final byte[] regionColors = regionPatch.getColors();

    assertArrayEquals(expected, colors, "an extracted patch keeps its own copy of the colors");
    assertArrayEquals(expected, regionColors, "a patch of the whole region keeps its own copy of the colors");
  }

  @Test
  void shouldAcceptImagesWithTheLargestPossiblePixelCount() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 1, Integer.MAX_VALUE);
    final int imageHeight = layout.getImageHeight();
    final int mapCount = layout.getMapCount();

    assertEquals(Integer.MAX_VALUE, imageHeight, "the largest pixel count is still accepted");
    assertEquals(1, mapCount);
  }

  @Test
  void shouldRejectInvalidDimensions() {
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(-1, 1, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 1, 1, 1, 0));
  }

  @Test
  void shouldRejectGridsWhoseMapIdsOrPixelCountsOverflow() {
    final MapLayout lastIds = MapLayout.of(Integer.MAX_VALUE - 1, 2, 1, 1, 1);
    final int lastId = lastIds.getMapId(1);

    assertEquals(Integer.MAX_VALUE, lastId);
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(Integer.MAX_VALUE, 2, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(1, OVERFLOWING_DIMENSION, OVERFLOWING_DIMENSION, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 16_777_216, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 1, 16_777_216, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, 1, 1, OVERFLOWING_DIMENSION, OVERFLOWING_DIMENSION));
  }

  @Test
  void shouldRejectMapCountsThatOverflowEvenWhenTheLastIdFits() {
    assertThrows(IllegalArgumentException.class, () -> MapLayout.of(0, OVERFLOWING_DIMENSION, 32_768, 1, 1));
  }

  @Test
  void shouldExtractRectanglesThatFillTheWholeRegion() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final byte[] image = createImage(100, SMALL_IMAGE_HEIGHT);
    final MapTilePatch patch = layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, 100, SMALL_IMAGE_HEIGHT);
    final byte[] colors = patch.getColors();

    assertArrayEquals(image, colors);
  }

  @Test
  void shouldRejectRectanglesThatAreNotInsideTheRegionOfTheMap() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final byte[] image = createImage(100, SMALL_IMAGE_HEIGHT);
    final byte[] wrongSize = new byte[10];

    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 13, SMALL_IMAGE_TOP, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, 101, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 114, SMALL_IMAGE_TOP, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, Integer.MAX_VALUE, SMALL_IMAGE_TOP, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 38, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, 1, 51));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, 89, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, -1, -1));
    assertThrows(IllegalArgumentException.class, () -> layout.extractPatch(wrongSize, 0, 14, SMALL_IMAGE_TOP, 1, 1));
  }

  @Test
  void shouldRejectMapIndicesOutsideOfTheGrid() {
    final MapLayout layout = MapLayout.of(0, 2, 1, TWO_MAP_SIZE, MAP_SIZE);
    final byte[] image = new byte[TWO_MAP_SIZE * MAP_SIZE];

    assertThrows(IndexOutOfBoundsException.class, () -> layout.getRegion(2));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getRegion(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(2));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.getMapId(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> layout.extractPatch(image, 2, 0, 0, 1, 1));
  }

  @Test
  void shouldMatchOnlyTheExactGridAndImage() {
    final MapLayout layout = MapLayout.of(5, 2, 3, 200, 300);
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
  void shouldExtractRectanglesInMapCoordinates() {
    final MapLayout layout = MapLayout.of(3, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final byte[] image = createImage(100, SMALL_IMAGE_HEIGHT);
    final MapTilePatch patch = layout.extractPatch(image, 0, PATCH_LEFT, 40, 3, 2);
    final byte[] expected = copyRectangle(image, 100, 6, 1, 3, 2);
    final byte[] colors = patch.getColors();
    final int mapId = patch.getMapId();
    final int left = patch.getX();
    final int top = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();

    assertArrayEquals(expected, colors);
    assertEquals(3, mapId);
    assertEquals(PATCH_LEFT, left);
    assertEquals(40, top);
    assertEquals(3, width);
    assertEquals(2, height);
    assertThrows(NullPointerException.class, () -> layout.extractPatch(null, 0, PATCH_LEFT, 40, 3, 2));
  }

  @Test
  void shouldExtractOnePatchPerCoveredMap() {
    final MapLayout layout = MapLayout.of(7, 3, 1, MAP_SIZE, SHORT_IMAGE_HEIGHT);
    final byte[] image = createImage(MAP_SIZE, SHORT_IMAGE_HEIGHT);
    final List<MapTilePatch> patches = layout.extractAll(image);
    final int patchCount = patches.size();
    final MapTilePatch patch = patches.getFirst();
    final int mapId = patch.getMapId();
    final int left = patch.getX();
    final int top = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();

    assertEquals(1, patchCount);
    assertEquals(8, mapId);
    assertEquals(0, left);
    assertEquals(SHORT_IMAGE_TOP, top);
    assertEquals(MAP_SIZE, width);
    assertEquals(SHORT_IMAGE_HEIGHT, height);
    assertArrayEquals(image, colors);
  }

  @Test
  void shouldExtractEveryMapOfAFullGrid() {
    final MapLayout layout = MapLayout.of(0, 2, 2, TWO_MAP_SIZE, TWO_MAP_SIZE);
    final byte[] image = createImage(TWO_MAP_SIZE, TWO_MAP_SIZE);
    final List<MapTilePatch> patches = layout.extractAll(image);
    final int patchCount = patches.size();
    final MapTilePatch lastPatch = patches.getLast();
    final byte[] lastColors = lastPatch.getColors();
    final byte[] expectedLastColors = copyRectangle(image, TWO_MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE, MAP_SIZE);
    final int lastId = lastPatch.getMapId();

    assertEquals(4, patchCount);
    assertEquals(3, lastId);
    assertArrayEquals(expectedLastColors, lastColors);
  }

  @Test
  void shouldRejectImagesOfTheWrongSize() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 10, 10);
    final byte[] tooSmall = new byte[99];

    assertThrows(IllegalArgumentException.class, () -> layout.extractAll(tooSmall));
    assertThrows(NullPointerException.class, () -> layout.extractAll(null));
  }

  @Test
  void shouldRejectGridsThatAreTooLargeWhenTheirMapCountJustFits() {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
      MapLayout.of(0, 1, Integer.MAX_VALUE, 1, 1)
    );

    final String message = exception.getMessage();
    assertEquals("Map grid of 1x2147483647 maps is too large!", message);
  }

  @Test
  void shouldRejectEmptyRectanglesAsOutsideTheRegionOfTheMap() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final byte[] image = createImage(100, SMALL_IMAGE_HEIGHT);
    final IllegalArgumentException noWidth = assertThrows(IllegalArgumentException.class, () ->
      layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, 0, 1)
    );
    final IllegalArgumentException noHeight = assertThrows(IllegalArgumentException.class, () ->
      layout.extractPatch(image, 0, 14, SMALL_IMAGE_TOP, 1, 0)
    );

    final String noWidthMessage = noWidth.getMessage();
    final String noHeightMessage = noHeight.getMessage();
    assertEquals("Rectangle of 0x1 pixels at (14, 39) in map 0 is outside its region!", noWidthMessage);
    assertEquals("Rectangle of 1x0 pixels at (14, 39) in map 0 is outside its region!", noHeightMessage);
  }

  @Test
  void shouldCompareLayoutsByEveryPart() {
    final MapLayout layout = MapLayout.of(1, 2, 3, 4, 5);
    EqualityAssertions.assertEqualityContract(
      layout,
      MapLayout.of(1, 2, 3, 4, 5),
      MapLayout.of(9, 2, 3, 4, 5),
      MapLayout.of(1, 9, 3, 4, 5),
      MapLayout.of(1, 2, 9, 4, 5),
      MapLayout.of(1, 2, 3, 9, 5),
      MapLayout.of(1, 2, 3, 4, 9)
    );
  }

  @Test
  void shouldHashLayoutsByEveryPart() {
    final MapLayout layout = MapLayout.of(1, 2, 3, 4, 5);

    final int hash = layout.hashCode();
    final int expected = Objects.hash(1, 2, 3, 4, 5);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeLayoutsWithEveryPart() {
    final MapLayout layout = MapLayout.of(1, 2, 3, 4, 5);

    final String description = layout.toString();
    assertEquals("MapLayout{startMapId=1, columns=2, rows=3, imageWidth=4, imageHeight=5}", description);
  }
}
