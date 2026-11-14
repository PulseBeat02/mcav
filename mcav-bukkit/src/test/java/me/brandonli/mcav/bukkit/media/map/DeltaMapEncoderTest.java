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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DeltaMapEncoderTest {

  private static final int TWO_MAP_WIDTH = 256;

  private static final int TWO_PIXEL_IMAGE_TOP = 63;

  private static final int SMALL_IMAGE_HEIGHT = 50;
  private static final int SMALL_IMAGE_LEFT = 14;
  private static final int SMALL_IMAGE_TOP = 39;

  private static final int LAST_TILE_OFFSET = 112;

  private static final int LARGE_BUDGET = 1 << 20;

  private static final int DEFAULT_BUDGET_KIBIBYTES = 128;
  private static final int BYTES_PER_KIBIBYTE = 1024;

  private static final int MAP_SIZE = 128;

  private byte[] change(final byte[] image, final int imageWidth, final int left, final int top, final int width, final int height) {
    final byte[] changed = image.clone();
    for (int row = top; row < top + height; row++) {
      for (int column = left; column < left + width; column++) {
        final int index = row * imageWidth + column;
        changed[index] = (byte) (changed[index] + 1);
      }
    }

    return changed;
  }

  private void assertPatch(
    final MapTilePatch patch,
    final int mapId,
    final int left,
    final int top,
    final int width,
    final int height,
    final byte[] expectedColors
  ) {
    final int actualMapId = patch.getMapId();
    final int actualX = patch.getX();
    final int actualY = patch.getY();
    final int actualWidth = patch.getWidth();
    final int actualHeight = patch.getHeight();
    final byte[] colors = patch.getColors();

    assertEquals(mapId, actualMapId, "map id");
    assertEquals(left, actualX, "x");
    assertEquals(top, actualY, "y");
    assertEquals(width, actualWidth, "width");
    assertEquals(height, actualHeight, "height");
    assertArrayEquals(expectedColors, colors, "colors");
  }

  private void assertNothingSent(final List<MapTilePatch> patches) {
    final boolean empty = patches.isEmpty();

    assertTrue(empty, "no patches are sent");
  }

  private void assertPatchCount(final int expected, final List<MapTilePatch> patches) {
    final int count = patches.size();

    assertEquals(expected, count, "number of patches");
  }

  private void paint(final byte[] wall, final MapLayout layout, final List<MapTilePatch> patches) {
    for (final MapTilePatch patch : patches) {
      this.paintPatch(wall, layout, patch);
    }
  }

  private void paintPatch(final byte[] wall, final MapLayout layout, final MapTilePatch patch) {
    final int firstMapId = layout.getMapId(0);
    final int mapId = patch.getMapId();
    final MapRegion region = layout.getRegion(mapId - firstMapId);
    final int imageWidth = layout.getImageWidth();
    final int left = region.getSourceX() + patch.getX() - region.getLocalX();
    final int top = region.getSourceY() + patch.getY() - region.getLocalY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();
    for (int row = 0; row < height; row++) {
      System.arraycopy(colors, row * width, wall, (top + row) * imageWidth + left, width);
    }
  }

  @Test
  void shouldRejectInvalidArguments() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] wrongSize = new byte[10];

    assertThrows(NullPointerException.class, () -> new DeltaMapEncoder(null, LARGE_BUDGET));
    assertThrows(IllegalArgumentException.class, () -> new DeltaMapEncoder(layout, 0));
    assertThrows(NullPointerException.class, () -> encoder.encode(null));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(wrongSize));
  }

  @Test
  void shouldExposeItsLayoutAndTheDefaultBudget() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final MapLayout encoderLayout = encoder.getLayout();

    assertSame(layout, encoderLayout);

    final int expectedBudget = DEFAULT_BUDGET_KIBIBYTES * BYTES_PER_KIBIBYTE;
    final int defaultBudget = DeltaMapEncoder.DEFAULT_MAXIMUM_BYTES_PER_FRAME;
    assertEquals(expectedBudget, defaultBudget);
  }

  @Test
  void shouldSendEveryCoveredMapCompletelyInTheFirstFrame() {
    final MapLayout layout = MapLayout.of(10, 2, 1, TWO_MAP_WIDTH, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAXIMUM_BYTES_PER_FRAME);
    final byte[] image = MapLayoutTest.createImage(TWO_MAP_WIDTH, MAP_SIZE);
    final List<MapTilePatch> patches = encoder.encode(image);
    final MapTilePatch left = patches.get(0);
    final MapTilePatch right = patches.get(1);
    final byte[] leftColors = MapLayoutTest.copyRectangle(image, TWO_MAP_WIDTH, 0, 0, MAP_SIZE, MAP_SIZE);
    final byte[] rightColors = MapLayoutTest.copyRectangle(image, TWO_MAP_WIDTH, MAP_SIZE, 0, MAP_SIZE, MAP_SIZE);

    this.assertPatchCount(2, patches);
    this.assertPatch(left, 10, 0, 0, MAP_SIZE, MAP_SIZE, leftColors);
    this.assertPatch(right, 11, 0, 0, MAP_SIZE, MAP_SIZE, rightColors);
  }

  @Test
  void shouldSendNothingWhenTheFrameDidNotChange() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] sameImage = image.clone();
    final List<MapTilePatch> patches = encoder.encode(sameImage);

    this.assertNothingSent(patches);
  }

  @Test
  void shouldSendOnlyTheTileThatChanged() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] changed = this.change(image, MAP_SIZE, 20, 35, 8, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 16, 32, 16, 16);
    final MapTilePatch patch = patches.getFirst();

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 16, 32, 16, 16, expected);
  }

  @Test
  void shouldMergeAdjacentChangedTilesIntoOneRectangle() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] changed = this.change(image, MAP_SIZE, 10, 10, 20, 20);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 32, 32);
    final MapTilePatch patch = patches.getFirst();

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 0, 0, 32, 32, expected);
  }

  @Test
  void shouldMergeHorizontalTileRunsAwayFromTheFirstColumn() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] changed = this.change(image, MAP_SIZE, 32, 16, 32, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 32, 16, 32, 16);
    this.assertPatchCount(1, patches);

    final MapTilePatch patch = patches.getFirst();
    this.assertPatch(patch, 0, 32, 16, 32, 16, expected);
  }

  @Test
  void shouldSendSeparateRectanglesWhenTheyAreSmallerThanTheirBoundingBox() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] topLeft = this.change(image, MAP_SIZE, 0, 0, 4, 1);
    final byte[] belowTopLeft = this.change(topLeft, MAP_SIZE, 0, 16, 4, 1);
    final byte[] changed = this.change(belowTopLeft, MAP_SIZE, 16, 16, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch column = patches.get(0);
    final MapTilePatch single = patches.get(1);
    final byte[] columnColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 16, 32);
    final byte[] singleColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 16, 16, 16, 16);

    this.assertPatchCount(2, patches);
    this.assertPatch(column, 0, 0, 0, 16, 32, columnColors);
    this.assertPatch(single, 0, 16, 16, 16, 16, singleColors);
  }

  @Test
  void shouldSendTheBoundingBoxWhenItIsNotLargerThanTheSeparateRectangles() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, 2);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, 2);
    encoder.encode(image);
    final byte[] firstRow = this.change(image, MAP_SIZE, 0, 0, 4, 1);
    final byte[] secondRow = this.change(firstRow, MAP_SIZE, 0, 1, 4, 1);
    final byte[] changed = this.change(secondRow, MAP_SIZE, 16, 1, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 32, 2);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 0, TWO_PIXEL_IMAGE_TOP, 32, 2, expected);
  }

  @Test
  void shouldMergeChangesThatReachTheBottomOfTheMap() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] changed = this.change(image, MAP_SIZE, 0, 100, 4, 28);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 96, 16, 32);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 0, 96, 16, 32, expected);
  }

  @Test
  void shouldClipChangesToThePartOfTheMapTheImageCovers() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(100, SMALL_IMAGE_HEIGHT);
    final List<MapTilePatch> first = encoder.encode(image);
    final MapTilePatch full = first.getFirst();

    this.assertPatch(full, 0, SMALL_IMAGE_LEFT, SMALL_IMAGE_TOP, 100, SMALL_IMAGE_HEIGHT, image);

    final byte[] changed = this.change(image, 100, 0, 0, 2, 2);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 100, 0, 0, 2, 9);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, SMALL_IMAGE_LEFT, SMALL_IMAGE_TOP, 2, 9, expected);
  }

  @Test
  void shouldFindSeveralChangesInOneRowUpToTheRightEdge() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] left = this.change(image, MAP_SIZE, 0, 10, 4, 1);
    final byte[] changed = this.change(left, MAP_SIZE, 124, 10, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch leftPatch = patches.get(0);
    final MapTilePatch rightPatch = patches.get(1);
    final byte[] leftColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 16, 16);
    final byte[] rightColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, LAST_TILE_OFFSET, 0, 16, 16);

    this.assertPatchCount(2, patches);
    this.assertPatch(leftPatch, 0, 0, 0, 16, 16, leftColors);
    this.assertPatch(rightPatch, 0, LAST_TILE_OFFSET, 0, 16, 16, rightColors);
  }

  @Test
  void shouldHoldBackNoiseForSixFramesAndThenSendIt() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] noisy = this.change(image, MAP_SIZE, 40, 40, 3, 1);
    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      this.assertNothingSent(deferred);
    }

    final List<MapTilePatch> patches = encoder.encode(noisy);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(noisy, MAP_SIZE, 32, 32, 16, 16);
    final List<MapTilePatch> afterwards = encoder.encode(noisy);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 32, 32, 16, 16, expected);
    this.assertNothingSent(afterwards);
  }

  @Test
  void shouldForgetNoiseThatDisappearsOnItsOwn() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] noisy = this.change(image, MAP_SIZE, 0, 0, 3, 1);
    for (int frame = 0; frame < 5; frame++) {
      encoder.encode(noisy);
    }

    final List<MapTilePatch> reverted = encoder.encode(image);

    this.assertNothingSent(reverted);

    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      this.assertNothingSent(deferred);
    }

    final List<MapTilePatch> patches = encoder.encode(noisy);

    this.assertPatchCount(1, patches);
  }

  @Test
  void shouldSendRealMotionWhileHoldingBackNoise() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] motion = this.change(image, MAP_SIZE, 0, 0, 8, 1);
    final byte[] changed = this.change(motion, MAP_SIZE, 80, 80, 2, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 16, 16);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch state = snapshot.getFirst();
    final byte[] stateColors = state.getColors();

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 0, 0, 16, 16, expected);
    assertArrayEquals(motion, stateColors, "the noise is not part of what the clients display");
  }

  @Test
  void shouldShowOneWholeFrameWhenEveryMapChanges() {
    final int columns = 5;
    final int rows = 3;
    final int width = columns * MAP_SIZE;
    final int height = rows * MAP_SIZE;
    final int frameCount = 30;
    final int colorCount = 6;
    final MapLayout layout = MapLayout.of(0, columns, rows, width, height);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAXIMUM_BYTES_PER_FRAME);
    final byte[] wall = new byte[width * height];
    final List<byte[]> frames = new ArrayList<>();
    for (int frame = 0; frame < frameCount; frame++) {
      final byte[] image = new byte[width * height];
      Arrays.fill(image, (byte) ((frame % colorCount) + 1));
      frames.add(image);
      final List<MapTilePatch> patches = encoder.encode(image);
      this.paint(wall, layout, patches);

      final boolean showsOneFrame = frames.stream().anyMatch(shown -> Arrays.equals(shown, wall));
      assertTrue(showsOneFrame, "the wall shows one whole frame after frame %s".formatted(frame));
    }
  }

  @Test
  void shouldSkipAFrameTheCreditDoesNotCoverAndSendTheNextWhole() {
    final MapLayout layout = MapLayout.of(0, 2, 1, TWO_MAP_WIDTH, MAP_SIZE);
    final int oneMap = MAP_SIZE * MAP_SIZE + MapLayout.PATCH_OVERHEAD;
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, oneMap);
    final byte[] image = MapLayoutTest.createImage(TWO_MAP_WIDTH, MAP_SIZE);
    final byte[] second = this.change(image, TWO_MAP_WIDTH, 0, 0, TWO_MAP_WIDTH, MAP_SIZE);
    final byte[] third = this.change(second, TWO_MAP_WIDTH, 0, 0, TWO_MAP_WIDTH, MAP_SIZE);
    final List<MapTilePatch> first = encoder.encode(image);
    final List<MapTilePatch> skipped = encoder.encode(second);
    final List<MapTilePatch> whole = encoder.encode(third);
    final MapTilePatch left = whole.get(0);
    final MapTilePatch right = whole.get(1);
    final byte[] leftColors = MapLayoutTest.copyRectangle(third, TWO_MAP_WIDTH, 0, 0, MAP_SIZE, MAP_SIZE);
    final byte[] rightColors = MapLayoutTest.copyRectangle(third, TWO_MAP_WIDTH, MAP_SIZE, 0, MAP_SIZE, MAP_SIZE);

    this.assertPatchCount(2, first);
    this.assertNothingSent(skipped);
    this.assertPatchCount(2, whole);
    this.assertPatch(left, 0, 0, 0, MAP_SIZE, MAP_SIZE, leftColors);
    this.assertPatch(right, 1, 0, 0, MAP_SIZE, MAP_SIZE, rightColors);
  }

  @Test
  void shouldSendAFrameLargerThanTheBudgetOnceTheCreditCoversIt() {
    final MapLayout layout = MapLayout.of(0, 2, 1, TWO_MAP_WIDTH, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(TWO_MAP_WIDTH, MAP_SIZE);
    final List<MapTilePatch> first = encoder.encode(image);

    this.assertPatchCount(2, first);

    final byte[] changed = this.change(image, TWO_MAP_WIDTH, MAP_SIZE, 0, 16, 16);
    final int tileBytes = 16 * 16 + MapLayout.PATCH_OVERHEAD;
    for (int frame = 1; frame < tileBytes; frame++) {
      final List<MapTilePatch> waiting = encoder.encode(changed);
      this.assertNothingSent(waiting);
    }

    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, TWO_MAP_WIDTH, MAP_SIZE, 0, 16, 16);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 1, 0, 0, 16, 16, expected);
  }

  @Test
  void shouldRejectImagesOfTheWrongSizeInEveryFrame() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] tooLong = Arrays.copyOf(image, image.length + 1);

    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> encoder.encode(tooLong));
    final String message = exception.getMessage();

    assertEquals("Image length 16385 does not match the layout's 16384 pixels!", message);
  }

  @Test
  void shouldCountTilesFromTheLeftEdgeOfTheMapTheImageStartsAt() {
    final MapLayout layout = MapLayout.of(0, 1, 1, 100, SMALL_IMAGE_HEIGHT);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(100, SMALL_IMAGE_HEIGHT);
    encoder.encode(image);
    final byte[] changed = this.change(image, 100, 0, 0, 21, 2);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 100, 0, 0, 34, 9);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, SMALL_IMAGE_LEFT, SMALL_IMAGE_TOP, 34, 9, expected);
  }

  @Test
  void shouldMergeTilesDownwardsOnlyWhileTheWholeRowChanged() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] block = this.change(image, MAP_SIZE, 0, 0, 32, 32);
    final byte[] changed = this.change(block, MAP_SIZE, LAST_TILE_OFFSET, 32, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch merged = patches.get(0);
    final MapTilePatch distant = patches.get(1);
    final byte[] mergedColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 32, 32);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, LAST_TILE_OFFSET, 32, 16, 16);

    this.assertPatchCount(2, patches);
    this.assertPatch(merged, 0, 0, 0, 32, 32, mergedColors);
    this.assertPatch(distant, 0, LAST_TILE_OFFSET, 32, 16, 16, distantColors);
  }

  @Test
  void shouldMergeTheLastTwoTilesWithoutReadingBeyondTheFinalRow() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] corner = this.change(image, MAP_SIZE, 0, 0, 16, 16);
    final byte[] changed = this.change(corner, MAP_SIZE, 96, LAST_TILE_OFFSET, 32, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    this.assertPatchCount(2, patches);

    final byte[] first = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 0, 16, 16);
    final byte[] last = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 96, LAST_TILE_OFFSET, 32, 16);
    this.assertPatch(patches.get(0), 0, 0, 0, 16, 16, first);
    this.assertPatch(patches.get(1), 0, 96, LAST_TILE_OFFSET, 32, 16, last);
  }

  @Test
  void shouldMergeTilesOfLowerRowsSideways() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] row = this.change(image, MAP_SIZE, 0, 16, 32, 16);
    final byte[] changed = this.change(row, MAP_SIZE, LAST_TILE_OFFSET, 32, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch merged = patches.get(0);
    final MapTilePatch distant = patches.get(1);
    final byte[] mergedColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 16, 32, 16);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, LAST_TILE_OFFSET, 32, 16, 16);

    this.assertPatchCount(2, patches);
    this.assertPatch(merged, 0, 0, 16, 32, 16, mergedColors);
    this.assertPatch(distant, 0, LAST_TILE_OFFSET, 32, 16, 16, distantColors);
  }

  @Test
  void shouldNeverMergeARunPastTheRightEdgeIntoTheNextRow() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] rightEdge = this.change(image, MAP_SIZE, LAST_TILE_OFFSET, 0, 16, 16);
    final byte[] nextRow = this.change(rightEdge, MAP_SIZE, 0, 16, 16, 16);
    final byte[] changed = this.change(nextRow, MAP_SIZE, 48, 64, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch edge = patches.get(0);
    final MapTilePatch wrapped = patches.get(1);
    final MapTilePatch distant = patches.get(2);
    final byte[] edgeColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, LAST_TILE_OFFSET, 0, 16, 16);
    final byte[] wrappedColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 0, 16, 16, 16);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 48, 64, 16, 16);

    this.assertPatchCount(3, patches);
    this.assertPatch(edge, 0, LAST_TILE_OFFSET, 0, 16, 16, edgeColors);
    this.assertPatch(wrapped, 0, 0, 16, 16, 16, wrappedColors);
    this.assertPatch(distant, 0, 48, 64, 16, 16, distantColors);
  }

  @Test
  void shouldMeasureTheBoundingBoxFromItsLeftEdge() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, 2);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, 2);
    encoder.encode(image);
    final byte[] firstRow = this.change(image, MAP_SIZE, 16, 0, 4, 1);
    final byte[] secondRow = this.change(firstRow, MAP_SIZE, 16, 1, 4, 1);
    final byte[] changed = this.change(secondRow, MAP_SIZE, 32, 1, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, MAP_SIZE, 16, 0, 32, 2);

    this.assertPatchCount(1, patches);
    this.assertPatch(patch, 0, 16, TWO_PIXEL_IMAGE_TOP, 32, 2, expected);
  }

  @Test
  void shouldReturnAnEmptySnapshotBeforeTheFirstFrame() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final List<MapTilePatch> snapshot = encoder.snapshot();

    this.assertNothingSent(snapshot);
  }

  @Test
  void shouldDescribeWhatTheClientsDisplayInTheSnapshot() {
    final MapLayout layout = MapLayout.of(4, 3, 1, MAP_SIZE, 64);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, 64);
    encoder.encode(image);
    final byte[] changed = this.change(image, MAP_SIZE, 0, 0, 30, 30);
    encoder.encode(changed);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch patch = snapshot.getFirst();

    this.assertPatchCount(1, snapshot);
    this.assertPatch(patch, 5, 0, 32, MAP_SIZE, 64, changed);
  }

  @Test
  void shouldLeaveAFrameTheCreditDoesNotCoverOutOfTheSnapshot() {
    final MapLayout layout = MapLayout.of(0, 2, 1, TWO_MAP_WIDTH, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(TWO_MAP_WIDTH, MAP_SIZE);
    encoder.encode(image);
    final byte[] changed = this.change(image, TWO_MAP_WIDTH, 0, 0, TWO_MAP_WIDTH, MAP_SIZE);
    final List<MapTilePatch> skipped = encoder.encode(changed);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch left = snapshot.getFirst();
    final byte[] leftColors = MapLayoutTest.copyRectangle(image, TWO_MAP_WIDTH, 0, 0, MAP_SIZE, MAP_SIZE);

    this.assertNothingSent(skipped);
    this.assertPatchCount(2, snapshot);
    this.assertPatch(left, 0, 0, 0, MAP_SIZE, MAP_SIZE, leftColors);
  }

  @Test
  void shouldSendTheNextFrameCompletelyAgainAfterAReset() {
    final MapLayout layout = MapLayout.of(0, 2, 1, TWO_MAP_WIDTH, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(TWO_MAP_WIDTH, MAP_SIZE);
    encoder.encode(image);
    encoder.reset();
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final List<MapTilePatch> afterReset = encoder.encode(image);
    final MapTilePatch patch = afterReset.getFirst();
    final byte[] leftColors = MapLayoutTest.copyRectangle(image, TWO_MAP_WIDTH, 0, 0, MAP_SIZE, MAP_SIZE);

    this.assertNothingSent(snapshot);
    this.assertPatchCount(2, afterReset);
    this.assertPatch(patch, 0, 0, 0, MAP_SIZE, MAP_SIZE, leftColors);
  }

  @Test
  void shouldForgetHeldBackNoiseOnReset() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final byte[] noisy = this.change(image, MAP_SIZE, 0, 0, 3, 1);
    for (int frame = 0; frame < 5; frame++) {
      encoder.encode(noisy);
    }

    encoder.reset();
    encoder.encode(image);
    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      this.assertNothingSent(deferred);
    }

    final List<MapTilePatch> patches = encoder.encode(noisy);

    this.assertPatchCount(1, patches);
  }

  @Test
  void shouldKeepAnEarlierSnapshotWhenALaterFrameChangesTheMap() {
    final MapLayout layout = MapLayout.of(0, 1, 1, MAP_SIZE, MAP_SIZE);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(MAP_SIZE, MAP_SIZE);
    encoder.encode(image);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final byte[] changed = this.change(image, MAP_SIZE, 0, 0, MAP_SIZE, MAP_SIZE);
    final List<MapTilePatch> patches = encoder.encode(changed);

    this.assertPatchCount(1, patches);

    this.assertPatchCount(1, snapshot);
    final MapTilePatch patch = snapshot.getFirst();
    this.assertPatch(patch, 0, 0, 0, MAP_SIZE, MAP_SIZE, image);
  }
}
