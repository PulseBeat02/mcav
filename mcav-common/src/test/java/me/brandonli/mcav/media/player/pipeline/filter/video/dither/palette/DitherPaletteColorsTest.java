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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DitherPaletteColorsTest {

  @Test
  void shouldCreateAPaletteFromAListOfColors() {
    final List<Integer> colors = List.of(0x000000, 0xFF0000, 0x00FF00);
    final DitherPalette palette = DitherPalette.colors(colors);
    final int[] opaqueColors = palette.getPalette();
    final int[] expected = new int[] { 0xFF000000, 0xFFFF0000, 0xFF00FF00 };
    assertArrayEquals(expected, opaqueColors);
  }

  @Test
  void shouldCopyTheColorArrayDuringConstruction() {
    final int[] colors = { 0x000000, 0xFFFFFF };
    final DitherPalette palette = DitherPalette.colors(colors);
    colors[1] = 0xFF0000;

    final int[] paletteColors = palette.getPalette();
    final int[] expectedPalette = new int[] { 0xFF000000, 0xFFFFFFFF };
    assertArrayEquals(expectedPalette, paletteColors, "changing the array afterward does not change the palette");

    final int[] expectedColors = new int[] { 0x000000, 0xFF0000 };
    assertArrayEquals(expectedColors, colors, "and the palette never writes its opaque colors into the array");
  }

  @Test
  void shouldRejectMissingColors() {
    final List<Integer> withNull = Arrays.asList(0x000000, null);
    assertThrows(NullPointerException.class, () -> DitherPalette.colors(withNull));
    assertThrows(NullPointerException.class, () -> DitherPalette.colors((List<Integer>) null));
  }

  @Test
  void shouldRejectColorListsOfNoColorsOrMoreThan256Colors() {
    final List<Integer> none = List.of();
    final List<Integer> tooMany = Collections.nCopies(257, 0x000000);
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors(none));
    assertThrows(IllegalArgumentException.class, () -> DitherPalette.colors(tooMany));
  }
}
