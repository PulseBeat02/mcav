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
package me.brandonli.mcav.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The six-bit map alphabet: symbol s is map colour s + 4, padded to whole rows with symbol 0. */
final class MapAlphabetTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MapAlphabet.class);
  }

  @Test
  void mapsSymbolsToColoursAndBack() throws Mcv2Exception {
    final byte[] colors = MapAlphabet.toMapColors(new byte[] { 0, 63, 1 });
    assertEquals(128, colors.length);
    assertEquals(4, colors[0]);
    assertEquals(67, colors[1]);
    assertEquals(5, colors[2]);
    assertEquals(4, colors[127]);
    assertArrayEquals(new byte[] { 0, 63 }, MapAlphabet.fromMapColors(new byte[] { 4, 67 }));
    assertEquals(0, MapAlphabet.toMapColors(new byte[0]).length);
    assertEquals(16384, MapAlphabet.toMapColors(new byte[16384]).length);
  }

  @Test
  void refusesSymbolsAndColoursOutsideTheAlphabet() {
    assertThrows(IllegalArgumentException.class, () -> MapAlphabet.toMapColors(new byte[] { 64 }));
    assertThrows(IllegalArgumentException.class, () -> MapAlphabet.toMapColors(new byte[16385]));
    assertThrows(Mcv2Exception.class, () -> MapAlphabet.fromMapColors(new byte[] { 3 }));
    assertThrows(Mcv2Exception.class, () -> MapAlphabet.fromMapColors(new byte[] { 68 }));
  }
}
