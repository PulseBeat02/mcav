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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DeltaMapEncoder}.
 */
final class DeltaMapEncoderTest {

  private static final int LARGE_BUDGET = 1 << 20;

  /**
   * Changes every pixel inside a rectangle of the image to a different color.
   */
  private static byte[] change(final byte[] image, final int imageWidth, final int x, final int y, final int width, final int height) {
    final byte[] changed = image.clone();
    for (int row = y; row < y + height; row++) {
      for (int column = x; column < x + width; column++) {
        final int index = row * imageWidth + column;
        changed[index] = (byte) (changed[index] + 1);
      }
    }
    return changed;
  }

  private static void assertPatch(
    final MapTilePatch patch,
    final int mapId,
    final int x,
    final int y,
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
    assertEquals(x, actualX, "x");
    assertEquals(y, actualY, "y");
    assertEquals(width, actualWidth, "width");
    assertEquals(height, actualHeight, "height");
    assertArrayEquals(expectedColors, colors, "colors");
  }

  private static void assertNothingSent(final List<MapTilePatch> patches) {
    final boolean empty = patches.isEmpty();

    assertTrue(empty, "no patches are sent");
  }

  private static void assertPatchCount(final int expected, final List<MapTilePatch> patches) {
    final int count = patches.size();

    assertEquals(expected, count, "number of patches");
  }

  @Test
  void rejectsInvalidArguments() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] wrongSize = new byte[10];

    assertThrows(NullPointerException.class, () -> new DeltaMapEncoder(null, LARGE_BUDGET));
    assertThrows(IllegalArgumentException.class, () -> new DeltaMapEncoder(layout, 0));
    assertThrows(NullPointerException.class, () -> encoder.encode(null));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(wrongSize));
  }

  @Test
  void exposesItsLayoutAndTheDefaultBudget() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final MapLayout encoderLayout = encoder.getLayout();

    assertSame(layout, encoderLayout);
    assertEquals(128 * 1024, DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME);
  }

  @Test
  void sendsEveryCoveredMapCompletelyInTheFirstFrame() {
    final MapLayout layout = new MapLayout(10, 2, 1, 256, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, DeltaMapEncoder.DEFAULT_MAX_BYTES_PER_FRAME);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    final List<MapTilePatch> patches = encoder.encode(image);
    final MapTilePatch left = patches.get(0);
    final MapTilePatch right = patches.get(1);
    final byte[] leftColors = MapLayoutTest.copyRectangle(image, 256, 0, 0, 128, 128);
    final byte[] rightColors = MapLayoutTest.copyRectangle(image, 256, 128, 0, 128, 128);

    assertPatchCount(2, patches);
    assertPatch(left, 10, 0, 0, 128, 128, leftColors);
    assertPatch(right, 11, 0, 0, 128, 128, rightColors);
  }

  @Test
  void sendsNothingWhenTheFrameDidNotChange() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] sameImage = image.clone();
    final List<MapTilePatch> patches = encoder.encode(sameImage);

    assertNothingSent(patches);
  }

  @Test
  void sendsOnlyTheTileThatChanged() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] changed = change(image, 128, 20, 35, 8, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 16, 32, 16, 16);
    final MapTilePatch patch = patches.getFirst();

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 16, 32, 16, 16, expected);
  }

  @Test
  void mergesAdjacentChangedTilesIntoOneRectangle() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] changed = change(image, 128, 10, 10, 20, 20);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 32, 32);
    final MapTilePatch patch = patches.getFirst();

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 0, 0, 32, 32, expected);
  }

  @Test
  void mergesHorizontalTileRunsAwayFromTheFirstColumn() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] changed = change(image, 128, 32, 16, 32, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 32, 16, 32, 16);
    assertPatchCount(1, patches);
    final MapTilePatch patch = patches.getFirst();
    assertPatch(patch, 0, 32, 16, 32, 16, expected);
  }

  @Test
  void sendsSeparateRectanglesWhenTheyAreSmallerThanTheirBoundingBox() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] topLeft = change(image, 128, 0, 0, 4, 1);
    final byte[] belowTopLeft = change(topLeft, 128, 0, 16, 4, 1);
    final byte[] changed = change(belowTopLeft, 128, 16, 16, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch column = patches.get(0);
    final MapTilePatch single = patches.get(1);
    final byte[] columnColors = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 16, 32);
    final byte[] singleColors = MapLayoutTest.copyRectangle(changed, 128, 16, 16, 16, 16);

    assertPatchCount(2, patches);
    assertPatch(column, 0, 0, 0, 16, 32, columnColors);
    assertPatch(single, 0, 16, 16, 16, 16, singleColors);
  }

  @Test
  void sendsTheBoundingBoxWhenItIsNotLargerThanTheSeparateRectangles() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 2);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 2);
    encoder.encode(image);
    final byte[] firstRow = change(image, 128, 0, 0, 4, 1);
    final byte[] secondRow = change(firstRow, 128, 0, 1, 4, 1);
    final byte[] changed = change(secondRow, 128, 16, 1, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 32, 2);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 0, 63, 32, 2, expected);
  }

  @Test
  void mergesChangesThatReachTheBottomOfTheMap() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] changed = change(image, 128, 0, 100, 4, 28);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 0, 96, 16, 32);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 0, 96, 16, 32, expected);
  }

  @Test
  void clipsChangesToThePartOfTheMapTheImageCovers() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(100, 50);
    final List<MapTilePatch> first = encoder.encode(image);
    final MapTilePatch full = first.getFirst();

    assertPatch(full, 0, 14, 39, 100, 50, image);

    final byte[] changed = change(image, 100, 0, 0, 2, 2);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 100, 0, 0, 2, 9);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 14, 39, 2, 9, expected);
  }

  @Test
  void findsSeveralChangesInOneRowUpToTheRightEdge() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] left = change(image, 128, 0, 10, 4, 1);
    final byte[] changed = change(left, 128, 124, 10, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch leftPatch = patches.get(0);
    final MapTilePatch rightPatch = patches.get(1);
    final byte[] leftColors = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 16, 16);
    final byte[] rightColors = MapLayoutTest.copyRectangle(changed, 128, 112, 0, 16, 16);

    assertPatchCount(2, patches);
    assertPatch(leftPatch, 0, 0, 0, 16, 16, leftColors);
    assertPatch(rightPatch, 0, 112, 0, 16, 16, rightColors);
  }

  @Test
  void holdsBackNoiseForSixFramesAndThenSendsIt() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] noisy = change(image, 128, 40, 40, 3, 1);
    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      assertNothingSent(deferred);
    }
    final List<MapTilePatch> patches = encoder.encode(noisy);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(noisy, 128, 32, 32, 16, 16);
    final List<MapTilePatch> afterwards = encoder.encode(noisy);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 32, 32, 16, 16, expected);
    assertNothingSent(afterwards);
  }

  @Test
  void forgetsNoiseThatDisappearsOnItsOwn() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] noisy = change(image, 128, 0, 0, 3, 1);
    for (int frame = 0; frame < 5; frame++) {
      encoder.encode(noisy);
    }
    final List<MapTilePatch> reverted = encoder.encode(image);

    assertNothingSent(reverted);

    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      assertNothingSent(deferred);
    }
    final List<MapTilePatch> patches = encoder.encode(noisy);

    assertPatchCount(1, patches);
  }

  @Test
  void sendsRealMotionWhileHoldingBackNoise() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] motion = change(image, 128, 0, 0, 8, 1);
    final byte[] changed = change(motion, 128, 80, 80, 2, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 16, 16);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch state = snapshot.getFirst();
    final byte[] stateColors = state.getColors();

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 0, 0, 16, 16, expected);
    assertArrayEquals(motion, stateColors, "the noise is not part of what the clients display");
  }

  @Test
  void alwaysSendsTheMostUrgentMapEvenIfItExceedsTheBudget() {
    final MapLayout layout = new MapLayout(0, 2, 1, 256, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    final List<MapTilePatch> first = encoder.encode(image);
    final List<MapTilePatch> second = encoder.encode(image);
    final List<MapTilePatch> third = encoder.encode(image);
    final MapTilePatch firstPatch = first.getFirst();
    final MapTilePatch secondPatch = second.getFirst();
    final int firstMap = firstPatch.getMapId();
    final int secondMap = secondPatch.getMapId();

    assertPatchCount(1, first);
    assertPatchCount(1, second);
    assertEquals(0, firstMap);
    assertEquals(1, secondMap);
    assertNothingSent(third);
  }

  @Test
  void sendsSeveralMapsThatFitIntoTheBudgetTogether() {
    final MapLayout layout = new MapLayout(0, 2, 1, 256, 128);
    final int twoMaps = 2 * (128 * 128 + MapLayout.PATCH_OVERHEAD);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, twoMaps);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    final List<MapTilePatch> patches = encoder.encode(image);

    assertPatchCount(2, patches);
  }

  @Test
  void sendsMapsThatHaveBeenWaitingBeforeMapsWithMoreChanges() {
    final MapLayout layout = new MapLayout(20, 2, 1, 256, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 600);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    encoder.encode(image);
    encoder.encode(image);
    final byte[] bigLeftChange = change(image, 256, 0, 0, 32, 16);
    final byte[] smallRightChange = change(bigLeftChange, 256, 128, 0, 16, 1);
    final List<MapTilePatch> third = encoder.encode(smallRightChange);
    final MapTilePatch leftPatch = third.getFirst();
    final byte[] leftColors = MapLayoutTest.copyRectangle(smallRightChange, 256, 0, 0, 32, 16);

    assertPatchCount(1, third);
    assertPatch(leftPatch, 20, 0, 0, 32, 16, leftColors);

    final byte[] anotherBigLeftChange = change(smallRightChange, 256, 0, 32, 32, 16);
    final List<MapTilePatch> fourth = encoder.encode(anotherBigLeftChange);
    final MapTilePatch rightPatch = fourth.getFirst();
    final byte[] rightColors = MapLayoutTest.copyRectangle(anotherBigLeftChange, 256, 128, 0, 16, 16);

    assertPatchCount(1, fourth);
    assertPatch(rightPatch, 21, 0, 0, 16, 16, rightColors);

    final List<MapTilePatch> fifth = encoder.encode(anotherBigLeftChange);
    final MapTilePatch waitingLeftPatch = fifth.getFirst();
    final byte[] waitingLeftColors = MapLayoutTest.copyRectangle(anotherBigLeftChange, 256, 0, 32, 32, 16);

    assertPatchCount(1, fifth);
    assertPatch(waitingLeftPatch, 20, 0, 32, 32, 16, waitingLeftColors);
  }

  @Test
  void rejectsImagesOfTheWrongSizeInEveryFrame() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    // a longer image is only noticed by the encoder itself: comparing it against the remembered state reads the
    // expected number of bytes and finds no difference
    final byte[] tooLong = Arrays.copyOf(image, image.length + 1);

    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> encoder.encode(tooLong));
    final String message = exception.getMessage();

    assertEquals("Image size does not match layout", message);
  }

  @Test
  void sendsTheMapWithTheMostChangedPixelsFirstAndIgnoresHeldBackNoise() {
    final MapLayout layout = new MapLayout(0, 2, 1, 64, 16);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(64, 16);
    encoder.encode(image);

    // the left map changes five pixels and holds back three as noise, the right map changes six, so the right map is
    // the more urgent one as long as the left map counts neither the noise nor a pixel beyond its tile
    final byte[] leftChange = change(image, 64, 0, 0, 5, 1);
    final byte[] leftNoise = change(leftChange, 64, 16, 0, 3, 1);
    final byte[] changed = change(leftNoise, 64, 32, 0, 6, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch right = patches.get(0);
    final MapTilePatch left = patches.get(1);
    final byte[] rightColors = MapLayoutTest.copyRectangle(changed, 64, 32, 0, 16, 8);
    final byte[] leftColors = MapLayoutTest.copyRectangle(changed, 64, 0, 0, 16, 8);

    assertPatchCount(2, patches);
    assertPatch(right, 1, 0, 56, 16, 8, rightColors);
    assertPatch(left, 0, 96, 56, 16, 8, leftColors);
  }

  @Test
  void countsTilesFromTheLeftEdgeOfTheMapTheImageStartsAt() {
    final MapLayout layout = new MapLayout(0, 1, 1, 100, 50);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(100, 50);
    encoder.encode(image);
    // the image starts at column 14 of the map, so the change covers the first three tiles of the map, not one
    final byte[] changed = change(image, 100, 0, 0, 21, 2);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 100, 0, 0, 34, 9);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 14, 39, 34, 9, expected);
  }

  @Test
  void mergesTilesDownwardsOnlyWhileTheWholeRowChanged() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    // a block of two by two tiles, and one tile far away that makes a bounding box around everything too expensive
    final byte[] block = change(image, 128, 0, 0, 32, 32);
    final byte[] changed = change(block, 128, 112, 32, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch merged = patches.get(0);
    final MapTilePatch distant = patches.get(1);
    final byte[] mergedColors = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 32, 32);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, 128, 112, 32, 16, 16);

    assertPatchCount(2, patches);
    assertPatch(merged, 0, 0, 0, 32, 32, mergedColors);
    assertPatch(distant, 0, 112, 32, 16, 16, distantColors);
  }

  @Test
  void mergesTheLastTwoTilesWithoutReadingBeyondTheFinalRow() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] corner = change(image, 128, 0, 0, 16, 16);
    final byte[] changed = change(corner, 128, 96, 112, 32, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    assertPatchCount(2, patches);
    final byte[] first = MapLayoutTest.copyRectangle(changed, 128, 0, 0, 16, 16);
    final byte[] last = MapLayoutTest.copyRectangle(changed, 128, 96, 112, 32, 16);
    assertPatch(patches.get(0), 0, 0, 0, 16, 16, first);
    assertPatch(patches.get(1), 0, 96, 112, 32, 16, last);
  }

  @Test
  void mergesTilesOfLowerRowsSideways() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    // two neighboring tiles of the second tile row, and one tile far away, so the merged rectangles beat a bounding box
    final byte[] row = change(image, 128, 0, 16, 32, 16);
    final byte[] changed = change(row, 128, 112, 32, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch merged = patches.get(0);
    final MapTilePatch distant = patches.get(1);
    final byte[] mergedColors = MapLayoutTest.copyRectangle(changed, 128, 0, 16, 32, 16);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, 128, 112, 32, 16, 16);

    assertPatchCount(2, patches);
    assertPatch(merged, 0, 0, 16, 32, 16, mergedColors);
    assertPatch(distant, 0, 112, 32, 16, 16, distantColors);
  }

  @Test
  void neverMergesARunPastTheRightEdgeIntoTheNextRow() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    // the last tile of the first row and the first tile of the second row touch in the tile array but not on the map,
    // and one tile far away keeps a bounding box around everything from winning
    final byte[] rightEdge = change(image, 128, 112, 0, 16, 16);
    final byte[] nextRow = change(rightEdge, 128, 0, 16, 16, 16);
    final byte[] changed = change(nextRow, 128, 48, 64, 16, 16);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch edge = patches.get(0);
    final MapTilePatch wrapped = patches.get(1);
    final MapTilePatch distant = patches.get(2);
    final byte[] edgeColors = MapLayoutTest.copyRectangle(changed, 128, 112, 0, 16, 16);
    final byte[] wrappedColors = MapLayoutTest.copyRectangle(changed, 128, 0, 16, 16, 16);
    final byte[] distantColors = MapLayoutTest.copyRectangle(changed, 128, 48, 64, 16, 16);

    assertPatchCount(3, patches);
    assertPatch(edge, 0, 112, 0, 16, 16, edgeColors);
    assertPatch(wrapped, 0, 0, 16, 16, 16, wrappedColors);
    assertPatch(distant, 0, 48, 64, 16, 16, distantColors);
  }

  @Test
  void measuresTheBoundingBoxFromItsLeftEdge() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 2);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 2);
    encoder.encode(image);
    // three tiles that start at column 16 of the map, whose bounding box is as small as the separate rectangles
    final byte[] firstRow = change(image, 128, 16, 0, 4, 1);
    final byte[] secondRow = change(firstRow, 128, 16, 1, 4, 1);
    final byte[] changed = change(secondRow, 128, 32, 1, 4, 1);
    final List<MapTilePatch> patches = encoder.encode(changed);
    final MapTilePatch patch = patches.getFirst();
    final byte[] expected = MapLayoutTest.copyRectangle(changed, 128, 16, 0, 32, 2);

    assertPatchCount(1, patches);
    assertPatch(patch, 0, 16, 63, 32, 2, expected);
  }

  @Test
  void snapshotIsEmptyBeforeTheFirstFrame() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final List<MapTilePatch> snapshot = encoder.snapshot();

    assertNothingSent(snapshot);
  }

  @Test
  void snapshotDescribesWhatTheClientsDisplay() {
    final MapLayout layout = new MapLayout(4, 3, 1, 128, 64);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 64);
    encoder.encode(image);
    final byte[] changed = change(image, 128, 0, 0, 30, 30);
    encoder.encode(changed);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch patch = snapshot.getFirst();

    assertPatchCount(1, snapshot);
    assertPatch(patch, 5, 0, 32, 128, 64, changed);
  }

  @Test
  void snapshotLeavesOutMapsThatWereNeverSent() {
    final MapLayout layout = new MapLayout(0, 2, 1, 256, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    encoder.encode(image);
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final MapTilePatch patch = snapshot.getFirst();
    final int mapId = patch.getMapId();

    assertPatchCount(1, snapshot);
    assertEquals(0, mapId);
  }

  @Test
  void resetSendsTheNextFrameCompletelyAgain() {
    final MapLayout layout = new MapLayout(0, 2, 1, 256, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1);
    final byte[] image = MapLayoutTest.createImage(256, 128);
    encoder.encode(image);
    encoder.reset();
    final List<MapTilePatch> snapshot = encoder.snapshot();
    final List<MapTilePatch> afterReset = encoder.encode(image);
    final MapTilePatch patch = afterReset.getFirst();
    final byte[] leftColors = MapLayoutTest.copyRectangle(image, 256, 0, 0, 128, 128);

    assertNothingSent(snapshot);
    assertPatchCount(1, afterReset);
    assertPatch(patch, 0, 0, 0, 128, 128, leftColors);
  }

  @Test
  void resetForgetsHeldBackNoise() {
    final MapLayout layout = new MapLayout(0, 1, 1, 128, 128);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, LARGE_BUDGET);
    final byte[] image = MapLayoutTest.createImage(128, 128);
    encoder.encode(image);
    final byte[] noisy = change(image, 128, 0, 0, 3, 1);
    for (int frame = 0; frame < 5; frame++) {
      encoder.encode(noisy);
    }
    encoder.reset();
    encoder.encode(image);
    for (int frame = 0; frame < 6; frame++) {
      final List<MapTilePatch> deferred = encoder.encode(noisy);
      assertNothingSent(deferred);
    }
    final List<MapTilePatch> patches = encoder.encode(noisy);

    assertPatchCount(1, patches);
  }
}
