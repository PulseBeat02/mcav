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
  void shouldReadTimesFromTheStart() {
    final SeekPosition expected = new SeekPosition(false, 90_000L);
    final SeekPosition parsed = SeekPosition.parse("90");
    assertEquals(expected, parsed);

    final SeekPosition secondExpected = new SeekPosition(false, 90_000L);
    final SeekPosition seekPositionParsed = SeekPosition.parse("1:30");
    assertEquals(secondExpected, seekPositionParsed);

    final SeekPosition thirdExpected = new SeekPosition(false, 3_723_000L);
    final SeekPosition secondParsed = SeekPosition.parse("1:02:03");
    assertEquals(thirdExpected, secondParsed);

    final SeekPosition fourthExpected = new SeekPosition(false, 1_500L);
    final SeekPosition thirdParsed = SeekPosition.parse("1.5");
    assertEquals(fourthExpected, thirdParsed);

    final SeekPosition fifthExpected = new SeekPosition(false, 61_025L);
    final SeekPosition fourthParsed = SeekPosition.parse("1:01.025");
    assertEquals(fifthExpected, fourthParsed);

    final SeekPosition sixthExpected = new SeekPosition(false, 0L);
    final SeekPosition fifthParsed = SeekPosition.parse("0");
    assertEquals(sixthExpected, fifthParsed);

    final SeekPosition seventhExpected = new SeekPosition(false, SeekPosition.MAX_MILLIS);
    final SeekPosition sixthParsed = SeekPosition.parse("99:59:59.999");
    assertEquals(seventhExpected, sixthParsed);
  }

  @Test
  void shouldReadTimesFromTheCurrentPosition() {
    final SeekPosition expected = new SeekPosition(true, 10_000L);
    final SeekPosition parsed = SeekPosition.parse("+10");
    assertEquals(expected, parsed);

    final SeekPosition secondExpected = new SeekPosition(true, -60_000L);
    final SeekPosition seekPositionParsed = SeekPosition.parse("-1:00");
    assertEquals(secondExpected, seekPositionParsed);

    final SeekPosition thirdExpected = new SeekPosition(true, -250L);
    final SeekPosition secondParsed = SeekPosition.parse("-0.25");
    assertEquals(thirdExpected, secondParsed);
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
  void shouldRefuseWhatIsNotATime(final String text) {
    final SeekPosition parsed = SeekPosition.parse(text);
    assertNull(parsed, text);
  }

  @Test
  void shouldResolveAgainstTheCurrentPositionAndNeverBeforeTheStart() {
    final long resolved = new SeekPosition(false, 90_000L).resolve(5_000L);
    assertEquals(90_000L, resolved);

    final long secondResolved = new SeekPosition(true, 10_000L).resolve(5_000L);
    assertEquals(15_000L, secondResolved);

    final long thirdResolved = new SeekPosition(true, -10_000L).resolve(5_000L);
    assertEquals(0L, thirdResolved);
    assertThrows(NullPointerException.class, () -> SeekPosition.parse(null));
  }

  @Test
  void shouldFormatTimesAsPlayersReadThem() {
    final String formatted = SeekPosition.format(999L);
    assertEquals("0:00", formatted);

    final String seekPositionFormatted = SeekPosition.format(90_000L);
    assertEquals("1:30", seekPositionFormatted);

    final String secondFormatted = SeekPosition.format(3_599_000L);
    assertEquals("59:59", secondFormatted);

    final String thirdFormatted = SeekPosition.format(3_723_000L);
    assertEquals("1:02:03", thirdFormatted);
    assertThrows(IllegalArgumentException.class, () -> SeekPosition.format(-1L));
  }

  @Test
  void shouldFormatTheStartOfAVideo() {
    final String formatted = SeekPosition.format(0L);
    assertEquals("0:00", formatted);
  }
}
