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
package me.brandonli.mcav.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Pattern palettes: resolving endpoints and selector words, inline or through the frame's tables. */
final class PatternRecordTest {

  private static final byte[] INLINE = { 1, 2, 3, 4, 5, 6, 0, (byte) 0b1010_0101 };

  @Test
  void resolvesAnInlineRecordAlongColumns() throws Mcv2Exception {
    final PatternRecord record = PatternRecord.expand(INLINE, 0, 8, null, null);
    assertEquals(0x010203, record.getColor0());
    assertEquals(0x040506, record.getColor1());
    assertEquals(0, record.getOrientation());
    assertEquals(1, record.getSelector(0));
    assertEquals(0, record.getSelector(1));
    // orientation 0: the selector follows x and repeats down every row
    assertEquals(0x040506, record.colorAt(0, 7));
    assertEquals(0x010203, record.colorAt(1, 0));
  }

  @Test
  void resolvesTablesAlongRows() throws Mcv2Exception {
    final byte[] endpoints = { 9, 9, 9, 8, 8, 8, 7, 7, 7, 6, 6, 6 };
    final byte[] words = { 0, 0, 1, 1, 1, 0 };
    final byte[] data = { 0, 1, 1 };
    final PatternRecord record = PatternRecord.expand(data, 1, 16, endpoints, words);
    assertEquals(0x070707, record.getColor0());
    assertEquals(1, record.getOrientation());
    // orientation 1: the selector follows y
    assertEquals(0x060606, record.colorAt(5, 0));
    assertEquals(0x070707, record.colorAt(0, 1));
  }

  @Test
  void rejectsBrokenRecords() {
    assertThrows(Mcv2Exception.class, () -> PatternRecord.expand(INLINE, -1, 8, null, null));
    assertThrows(Mcv2Exception.class, () -> PatternRecord.expand(INLINE, 1, 8, null, null));
    assertThrows(Mcv2Exception.class, () -> PatternRecord.expand(new byte[] { 2, 0, 0 }, 0, 8, new byte[12], null));
    assertThrows(Mcv2Exception.class, () -> PatternRecord.expand(new byte[] { 0, 3 }, 0, 8, new byte[6], new byte[6]));
    final byte[] orientation = INLINE.clone();
    orientation[6] = 2;
    assertThrows(Mcv2Exception.class, () -> PatternRecord.expand(orientation, 0, 8, null, null));
  }
}
