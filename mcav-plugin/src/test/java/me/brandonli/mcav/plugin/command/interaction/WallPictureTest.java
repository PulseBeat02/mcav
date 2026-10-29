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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link WallPicture}: a 1280x720 source on a wall of 10x6 maps, 1280x768 pixels, as the deep review measured it.
 */
final class WallPictureTest {

  private static final int WALL_COLUMNS = 10;
  private static final int WALL_ROWS = 6;

  private static final int SOURCE_WIDTH = 1280;
  private static final int SOURCE_HEIGHT = 720;

  private static final int WALL_CENTER_X = 640;
  private static final int WALL_CENTER_Y = 384;
  private static final int SOURCE_CENTER_X = 640;
  private static final int SOURCE_CENTER_Y = 360;

  private static final int LAST_WALL_PIXEL_X = 1279;
  private static final int LAST_WALL_PIXEL_Y = 767;
  private static final int LAST_SOURCE_PIXEL_Y = 719;

  @Test
  void shouldScaleTheWallToTheSourceForAStretchedPicture() {
    final WallPicture picture = WallPicture.of(WALL_COLUMNS, WALL_ROWS, SOURCE_WIDTH, SOURCE_HEIGHT, true);

    final int[] source = picture.toSource(WALL_CENTER_X, WALL_CENTER_Y);
    assertArrayEquals(new int[] { SOURCE_CENTER_X, SOURCE_CENTER_Y }, source);

    final int[] pictureSource = picture.toSource(LAST_WALL_PIXEL_X, LAST_WALL_PIXEL_Y);
    assertArrayEquals(new int[] { 1279, LAST_SOURCE_PIXEL_Y }, pictureSource);

    final int[] secondSource = picture.toSource(0, 0);
    assertArrayEquals(new int[] { 0, 0 }, secondSource);
  }

  @Test
  void shouldShiftACentredPictureByItsBorderAndLeaveNoPixelThere() {
    final WallPicture picture = WallPicture.of(WALL_COLUMNS, WALL_ROWS, SOURCE_WIDTH, SOURCE_HEIGHT, false);

    final int[] source = picture.toSource(WALL_CENTER_X, WALL_CENTER_Y);
    assertArrayEquals(new int[] { SOURCE_CENTER_X, SOURCE_CENTER_Y }, source);

    final int[] pictureSource = picture.toSource(100, 24);
    assertArrayEquals(new int[] { 100, 0 }, pictureSource);

    final int[] secondSource = picture.toSource(100, 743);
    assertArrayEquals(new int[] { 100, LAST_SOURCE_PIXEL_Y }, secondSource);

    final int[] thirdSource = picture.toSource(100, 23);
    assertNull(thirdSource);

    final int[] fourthSource = picture.toSource(100, 744);
    assertNull(fourthSource);
  }

  @Test
  void shouldCutOffACentredPictureLargerThanTheWallEquallyOnEachSide() {
    final WallPicture picture = WallPicture.of(WALL_COLUMNS, WALL_ROWS, 1920, 1080, false);

    final int[] source = picture.toSource(0, 0);
    assertArrayEquals(new int[] { 320, 156 }, source);

    final int[] pictureSource = picture.toSource(LAST_WALL_PIXEL_X, LAST_WALL_PIXEL_Y);
    assertArrayEquals(new int[] { 1599, 923 }, pictureSource);
  }

  @Test
  void shouldLeaveNoPixelOnTheSidesOfACentredPictureNarrowerThanTheWall() {
    final WallPicture picture = WallPicture.of(4, 3, 384, 384, false);

    final int[] source = picture.toSource(63, 10);
    assertNull(source);

    final int[] pictureSource = picture.toSource(64, 10);
    assertArrayEquals(new int[] { 0, 10 }, pictureSource);

    final int[] secondSource = picture.toSource(448, 10);
    assertNull(secondSource);
  }
}
