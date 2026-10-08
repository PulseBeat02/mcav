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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link WallPicture}: a 1280x720 source on a wall of 10x6 maps, 1280x768 pixels, as the deep review measured it.
 */
final class WallPictureTest {

  @Test
  void aStretchedPictureScalesTheWallToTheSource() {
    final WallPicture picture = WallPicture.of(10, 6, 1280, 720, true);
    assertArrayEquals(new int[] { 640, 360 }, picture.toSource(640, 384));
    assertArrayEquals(new int[] { 1279, 719 }, picture.toSource(1279, 767));
    assertArrayEquals(new int[] { 0, 0 }, picture.toSource(0, 0));
  }

  @Test
  void aCentredPictureShiftsByItsBorderAndHasNoPixelThere() {
    final WallPicture picture = WallPicture.of(10, 6, 1280, 720, false);
    assertArrayEquals(new int[] { 640, 360 }, picture.toSource(640, 384));
    assertArrayEquals(new int[] { 100, 0 }, picture.toSource(100, 24));
    assertArrayEquals(new int[] { 100, 719 }, picture.toSource(100, 743));
    assertNull(picture.toSource(100, 23));
    assertNull(picture.toSource(100, 744));
  }

  @Test
  void aCentredPictureLargerThanTheWallIsCutOffEquallyOnEachSide() {
    final WallPicture picture = WallPicture.of(10, 6, 1920, 1080, false);
    assertArrayEquals(new int[] { 320, 156 }, picture.toSource(0, 0));
    assertArrayEquals(new int[] { 1599, 923 }, picture.toSource(1279, 767));
  }

  @Test
  void aCentredPictureNarrowerThanTheWallHasNoPixelOnItsSides() {
    final WallPicture picture = WallPicture.of(4, 3, 384, 384, false);
    assertNull(picture.toSource(63, 10));
    assertArrayEquals(new int[] { 0, 10 }, picture.toSource(64, 10));
    assertNull(picture.toSource(448, 10));
  }
}
