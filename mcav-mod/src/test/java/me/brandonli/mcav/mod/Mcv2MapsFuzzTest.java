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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class Mcv2MapsFuzzTest {

  private static final int HEADER_COLORS = 16;

  @FuzzTest(maxDuration = "30s")
  void shouldTakeNoMapForBothAPageAndAnAnchor(final FuzzedDataProvider data) {
    final byte[] colors = this.map(data);

    final boolean page = Mcv2Maps.isPage(colors);
    final boolean anchor = Mcv2Maps.isAnchor(colors);
    assertFalse(page && anchor);
  }

  // a page or an anchor with bits of its header flipped, sometimes cut short
  private byte[] map(final FuzzedDataProvider data) {
    final int[] symbols = this.symbols(data);
    final byte[] masks = data.consumeBytes(HEADER_COLORS);
    final byte[] base = MapFixtures.colors(symbols);
    final byte[] colors = MapFixtures.withFlippedBits(base, masks);
    final boolean whole = data.consumeBoolean();
    if (whole) {
      return colors;
    }

    final int length = data.consumeInt(0, colors.length);
    return Arrays.copyOf(colors, length);
  }

  private int[] symbols(final FuzzedDataProvider data) {
    final boolean page = data.consumeBoolean();
    if (page) {
      return MapFixtures.pageSymbols(7, 0);
    }

    return MapFixtures.anchorSymbols(1, 2, 3, 4, 1, 300);
  }
}
