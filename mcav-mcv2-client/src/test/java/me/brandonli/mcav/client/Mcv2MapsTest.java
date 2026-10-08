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
package me.brandonli.mcav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class Mcv2MapsTest {

  @Test
  void theSymbolsAreTheColoursFourToSixtySevenAndNothingElse() {
    final byte[] colours = { 4, 67, 3, 68, (byte) 200, 0, 5 };
    assertEquals(0, Mcv2Maps.symbol(colours, 0));
    assertEquals(63, Mcv2Maps.symbol(colours, 1));
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, Mcv2Maps.symbol(colours, 2));
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, Mcv2Maps.symbol(colours, 3));
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, Mcv2Maps.symbol(colours, 4), "colours are unsigned");
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, Mcv2Maps.symbol(colours, 5));
    assertEquals(1, Mcv2Maps.symbol(colours, 6));
  }

  @Test
  void aPageIsAWholeMapThatStartsWithItsMagicVersionAndWidth() {
    final byte[] page = MapFixtures.colours(MapFixtures.pageSymbols(7, 0));
    assertTrue(Mcv2Maps.isPage(page));
    assertFalse(Mcv2Maps.isAnchor(page));
    for (int index = 0; index < MapFixtures.PAGE_PREFIX.length; index++) {
      final byte[] changed = page.clone();
      changed[index] = (byte) (changed[index] == 4 ? 5 : 4);
      assertFalse(Mcv2Maps.isPage(changed), "symbol " + index + " of the prefix");
    }
    assertFalse(Mcv2Maps.isPage(Arrays.copyOf(page, Mcv2Maps.COLOURS - 1)));
    assertFalse(Mcv2Maps.isPage(Arrays.copyOf(page, Mcv2Maps.COLOURS + 1)));
  }

  @Test
  void anAnchorIsAWholeMapOfItsSignatureAndFieldsWhoseChecksumHolds() {
    final byte[] anchor = MapFixtures.colours(MapFixtures.anchorSymbols(1, 2, 4, 3, 2, 1234));
    assertTrue(Mcv2Maps.isAnchor(anchor));
    assertFalse(Mcv2Maps.isPage(anchor));
    assertFalse(Mcv2Maps.isAnchor(Arrays.copyOf(anchor, Mcv2Maps.COLOURS - 1)));
    for (int index = 0; index < MapFixtures.ANCHOR_SIGNATURE.length; index++) {
      final byte[] changed = anchor.clone();
      changed[index] = (byte) (changed[index] == 4 ? 5 : 4);
      assertFalse(Mcv2Maps.isAnchor(changed), "symbol " + index + " of the signature");
    }
    final byte[] checksum = anchor.clone();
    checksum[15] = (byte) (((checksum[15] - 4 + 1) & 63) + 4);
    assertFalse(Mcv2Maps.isAnchor(checksum));
    final byte[] outside = anchor.clone();
    outside[9] = 3;
    assertFalse(Mcv2Maps.isAnchor(outside), "a field outside the alphabet");
  }

  @Test
  void theChecksumIsTheSumOfTheSevenFieldsInSixBits() {
    // the fields add up to 381: only the low six bits, 61, are the checksum
    final int[] symbols = MapFixtures.anchorSymbols(63, 63, 63, 63, 3, 4095);
    assertEquals(61, symbols[15]);
    assertTrue(Mcv2Maps.isAnchor(MapFixtures.colours(symbols)));
  }

  @Test
  void anAnchorFacesOneOfFourDirections() {
    for (int facing = 0; facing <= 3; facing++) {
      assertTrue(Mcv2Maps.isAnchor(MapFixtures.colours(MapFixtures.anchorSymbols(0, 0, 1, 1, facing, 7))), "facing " + facing);
    }
    assertFalse(Mcv2Maps.isAnchor(MapFixtures.colours(MapFixtures.anchorSymbols(0, 0, 1, 1, 4, 7))));
  }
}
