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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SeekPositionTest {

  @Test
  void readsTimesFromTheStart() {
    assertEquals(new SeekPosition(false, 90_000L), SeekPosition.parse("90"));
    assertEquals(new SeekPosition(false, 90_000L), SeekPosition.parse("1:30"));
    assertEquals(new SeekPosition(false, 3_723_000L), SeekPosition.parse("1:02:03"));
    assertEquals(new SeekPosition(false, 1_500L), SeekPosition.parse("1.5"));
    assertEquals(new SeekPosition(false, 61_025L), SeekPosition.parse("1:01.025"));
    assertEquals(new SeekPosition(false, 0L), SeekPosition.parse("0"));
    assertEquals(new SeekPosition(false, SeekPosition.MAX_MILLIS), SeekPosition.parse("99:59:59.999"));
  }

  @Test
  void readsTimesFromTheCurrentPosition() {
    assertEquals(new SeekPosition(true, 10_000L), SeekPosition.parse("+10"));
    assertEquals(new SeekPosition(true, -60_000L), SeekPosition.parse("-1:00"));
    assertEquals(new SeekPosition(true, -250L), SeekPosition.parse("-0.25"));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      "+",
      "1:",
      ":30",
      "1:60",
      "1:60:00",
      "100:00:00",
      "1.",
      ".5",
      "1.2345",
      "1,5",
      "--5",
      "+-5",
      " 5",
      "5 ",
      "1e3",
      "٣",
      "360000",
      "1:2:3:4",
    }
  )
  void refusesWhatIsNotATime(final String text) {
    assertNull(SeekPosition.parse(text), text);
  }

  @Test
  void resolvesAgainstTheCurrentPositionAndNeverBeforeTheStart() {
    assertEquals(90_000L, new SeekPosition(false, 90_000L).resolve(5_000L));
    assertEquals(15_000L, new SeekPosition(true, 10_000L).resolve(5_000L));
    assertEquals(0L, new SeekPosition(true, -10_000L).resolve(5_000L));
    assertThrows(NullPointerException.class, () -> SeekPosition.parse(null));
  }

  @Test
  void formatsTimesAsPlayersReadThem() {
    assertEquals("0:00", SeekPosition.format(999L));
    assertEquals("1:30", SeekPosition.format(90_000L));
    assertEquals("59:59", SeekPosition.format(3_599_000L));
    assertEquals("1:02:03", SeekPosition.format(3_723_000L));
    assertThrows(IllegalArgumentException.class, () -> SeekPosition.format(-1L));
  }
}
