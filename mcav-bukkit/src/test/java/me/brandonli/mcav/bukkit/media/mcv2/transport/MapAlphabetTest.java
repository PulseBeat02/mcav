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
package me.brandonli.mcav.bukkit.media.mcv2.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class MapAlphabetTest {

  @Test
  void shouldBeAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(MapAlphabet.class);
  }

  @Test
  void shouldMapSymbolsToColorsAndBack() {
    final byte[] colors = MapAlphabet.toMapColors(new byte[] { 0, 63, 1 });
    final int colorCount = colors.length;
    assertEquals(128, colorCount);

    final byte firstColor = colors[0];
    assertEquals(4, firstColor);

    final byte secondColor = colors[1];
    assertEquals(67, secondColor);

    final byte thirdColor = colors[2];
    assertEquals(5, thirdColor);

    final byte lastColor = colors[127];
    assertEquals(4, lastColor);

    final byte[] expectedSymbols = new byte[] { 0, 63 };
    final byte[] symbols = MapAlphabet.fromMapColors(new byte[] { 4, 67 });
    assertArrayEquals(expectedSymbols, symbols);

    final byte[] emptyColors = MapAlphabet.toMapColors(new byte[0]);
    final int emptyColorCount = emptyColors.length;
    assertEquals(0, emptyColorCount);

    final byte[] fullColors = MapAlphabet.toMapColors(new byte[16384]);
    final int fullColorCount = fullColors.length;
    assertEquals(16384, fullColorCount);
  }

  @Test
  void shouldRefuseSymbolsAndColorsOutsideTheAlphabet() {
    assertThrows(IllegalArgumentException.class, () -> MapAlphabet.toMapColors(new byte[] { 64 }));
    assertThrows(IllegalArgumentException.class, () -> MapAlphabet.toMapColors(new byte[16385]));
    assertThrows(Mcv2Exception.class, () -> MapAlphabet.fromMapColors(new byte[] { 3 }));
    assertThrows(Mcv2Exception.class, () -> MapAlphabet.fromMapColors(new byte[] { 68 }));
  }

  @Test
  void shouldReadSymbolBytesAsUnsigned() {
    assertThrows(IllegalArgumentException.class, () -> MapAlphabet.toMapColors(new byte[] { (byte) 0xC0 }));
  }
}
