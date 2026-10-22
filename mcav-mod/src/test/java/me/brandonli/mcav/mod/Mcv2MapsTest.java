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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class Mcv2MapsTest {

  private static final int CHECKSUM = 15;

  @Test
  void shouldUseTheColoursFourToSixtySevenAndNothingElseAsSymbols() {
    final byte[] colours = { 4, 67, 3, 68, (byte) 200, 0, 5 };
    final int firstSymbol = Mcv2Maps.symbol(colours, 0);
    final int secondSymbol = Mcv2Maps.symbol(colours, 1);
    final int thirdSymbol = Mcv2Maps.symbol(colours, 2);
    final int fourthSymbol = Mcv2Maps.symbol(colours, 3);
    final int fifthSymbol = Mcv2Maps.symbol(colours, 4);
    final int sixthSymbol = Mcv2Maps.symbol(colours, 5);
    final int seventhSymbol = Mcv2Maps.symbol(colours, 6);
    assertEquals(0, firstSymbol);
    assertEquals(63, secondSymbol);
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, thirdSymbol);
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, fourthSymbol);
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, fifthSymbol, "colours are unsigned");
    assertEquals(Mcv2Maps.NOT_A_SYMBOL, sixthSymbol);
    assertEquals(1, seventhSymbol);
  }

  @Test
  void shouldTakeAWholeMapThatStartsWithItsMagicVersionAndWidthAsAPage() {
    final byte[] page = MapFixtures.colours(MapFixtures.pageSymbols(7, 0));
    final boolean readAsPage = Mcv2Maps.isPage(page);
    final boolean readAsAnchor = Mcv2Maps.isAnchor(page);
    assertTrue(readAsPage);
    assertFalse(readAsAnchor);
    for (int index = 0; index < MapFixtures.PAGE_PREFIX.length; index++) {
      final byte[] changed = page.clone();
      changed[index] = (byte) (changed[index] == 4 ? 5 : 4);
      final boolean changedReadAsPage = Mcv2Maps.isPage(changed);
      assertFalse(changedReadAsPage, "symbol " + index + " of the prefix");
    }
    final boolean shorterReadAsPage = Mcv2Maps.isPage(Arrays.copyOf(page, Mcv2Maps.COLOURS - 1));
    final boolean longerReadAsPage = Mcv2Maps.isPage(Arrays.copyOf(page, Mcv2Maps.COLOURS + 1));
    assertFalse(shorterReadAsPage);
    assertFalse(longerReadAsPage);
  }

  @Test
  void shouldTakeAWholeMapOfItsSignatureAndFieldsWhoseChecksumHoldsAsAnAnchor() {
    final byte[] anchor = MapFixtures.colours(MapFixtures.anchorSymbols(1, 2, 4, 3, 2, 1234));
    final boolean readAsAnchor = Mcv2Maps.isAnchor(anchor);
    final boolean readAsPage = Mcv2Maps.isPage(anchor);
    final boolean shorterReadAsAnchor = Mcv2Maps.isAnchor(Arrays.copyOf(anchor, Mcv2Maps.COLOURS - 1));
    assertTrue(readAsAnchor);
    assertFalse(readAsPage);
    assertFalse(shorterReadAsAnchor);
    for (int index = 0; index < MapFixtures.ANCHOR_SIGNATURE.length; index++) {
      final byte[] changed = anchor.clone();
      changed[index] = (byte) (changed[index] == 4 ? 5 : 4);
      final boolean changedReadAsAnchor = Mcv2Maps.isAnchor(changed);
      assertFalse(changedReadAsAnchor, "symbol " + index + " of the signature");
    }
    final byte[] checksum = anchor.clone();
    checksum[CHECKSUM] = (byte) (((checksum[CHECKSUM] - 4 + 1) & 63) + 4);
    final boolean wrongChecksumReadAsAnchor = Mcv2Maps.isAnchor(checksum);
    assertFalse(wrongChecksumReadAsAnchor);
    final byte[] outside = anchor.clone();
    outside[9] = 3;
    final boolean outsideReadAsAnchor = Mcv2Maps.isAnchor(outside);
    assertFalse(outsideReadAsAnchor, "a field outside the alphabet");
  }

  @Test
  void shouldSumTheSevenFieldsInSixBitsForTheChecksum() {
    final int[] symbols = MapFixtures.anchorSymbols(63, 63, 63, 63, 3, 4095);
    final int checksum = symbols[CHECKSUM];
    final boolean readAsAnchor = Mcv2Maps.isAnchor(MapFixtures.colours(symbols));
    assertEquals(61, checksum);
    assertTrue(readAsAnchor);
  }

  @Test
  void shouldLetAnAnchorFaceOneOfFourDirections() {
    for (int facing = 0; facing <= 3; facing++) {
      final boolean facingReadAsAnchor = Mcv2Maps.isAnchor(MapFixtures.colours(MapFixtures.anchorSymbols(0, 0, 1, 1, facing, 7)));
      assertTrue(facingReadAsAnchor, "facing " + facing);
    }
    final boolean fifthFacingReadAsAnchor = Mcv2Maps.isAnchor(MapFixtures.colours(MapFixtures.anchorSymbols(0, 0, 1, 1, 4, 7)));
    assertFalse(fifthFacingReadAsAnchor);
  }

  @Test
  void shouldNotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Maps.class);
  }
}
