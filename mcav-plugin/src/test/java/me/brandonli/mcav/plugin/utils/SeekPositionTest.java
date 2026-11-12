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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SeekPositionTest {

  private static final long MINUTES_POSITION_MILLISECONDS = 90_000L;
  private static final String MINUTES_POSITION_TEXT = "1:30";

  private static final long HOURS_POSITION_MILLISECONDS = 3_723_000L;
  private static final String HOURS_POSITION_TEXT = "1:02:03";

  private static final String ZERO_POSITION_TEXT = "0:00";

  private static final long RELATIVE_POSITION_MILLISECONDS = 10_000L;
  private static final long JUMP_BACK_POSITION_MILLISECONDS = -60_000L;

  private static final boolean RELATIVE = true;
  private static final boolean ABSOLUTE = false;

  @Test
  void shouldReadTimesFromTheStart() {
    final SeekPosition expected = SeekPosition.of(ABSOLUTE, MINUTES_POSITION_MILLISECONDS);
    final Optional<SeekPosition> parsed = SeekPosition.parse("90");
    final Optional<SeekPosition> expectedParsed = Optional.of(expected);
    assertEquals(expectedParsed, parsed);

    final SeekPosition secondExpected = SeekPosition.of(ABSOLUTE, MINUTES_POSITION_MILLISECONDS);
    final Optional<SeekPosition> seekPositionParsed = SeekPosition.parse(MINUTES_POSITION_TEXT);
    final Optional<SeekPosition> expectedSeekPosition = Optional.of(secondExpected);
    assertEquals(expectedSeekPosition, seekPositionParsed);

    final SeekPosition thirdExpected = SeekPosition.of(ABSOLUTE, HOURS_POSITION_MILLISECONDS);
    final Optional<SeekPosition> secondParsed = SeekPosition.parse(HOURS_POSITION_TEXT);
    final Optional<SeekPosition> expectedSecondParsed = Optional.of(thirdExpected);
    assertEquals(expectedSecondParsed, secondParsed);

    final SeekPosition fourthExpected = SeekPosition.of(ABSOLUTE, 1_500L);
    final Optional<SeekPosition> thirdParsed = SeekPosition.parse("1.5");
    final Optional<SeekPosition> expectedThirdParsed = Optional.of(fourthExpected);
    assertEquals(expectedThirdParsed, thirdParsed);

    final SeekPosition fifthExpected = SeekPosition.of(ABSOLUTE, 61_025L);
    final Optional<SeekPosition> fourthParsed = SeekPosition.parse("1:01.025");
    final Optional<SeekPosition> expectedFourthParsed = Optional.of(fifthExpected);
    assertEquals(expectedFourthParsed, fourthParsed);

    final SeekPosition sixthExpected = SeekPosition.of(ABSOLUTE, 0L);
    final Optional<SeekPosition> fifthParsed = SeekPosition.parse("0");
    final Optional<SeekPosition> expectedFifthParsed = Optional.of(sixthExpected);
    assertEquals(expectedFifthParsed, fifthParsed);

    final SeekPosition seventhExpected = SeekPosition.of(ABSOLUTE, SeekPosition.MAXIMUM_MILLISECONDS);
    final Optional<SeekPosition> sixthParsed = SeekPosition.parse("99:59:59.999");
    final Optional<SeekPosition> expectedSixthParsed = Optional.of(seventhExpected);
    assertEquals(expectedSixthParsed, sixthParsed);
  }

  @Test
  void shouldAcceptMinutesBeyondAnHourWhenNoHoursAreTyped() {
    final SeekPosition position = SeekPosition.of(ABSOLUTE, 4_500_000L);
    final Optional<SeekPosition> parsed = SeekPosition.parse("75:00");
    final Optional<SeekPosition> expected = Optional.of(position);
    assertEquals(expected, parsed);
  }

  @Test
  void shouldReadTimesFromTheCurrentPosition() {
    final SeekPosition expected = SeekPosition.of(RELATIVE, RELATIVE_POSITION_MILLISECONDS);
    final Optional<SeekPosition> parsed = SeekPosition.parse("+10");
    final Optional<SeekPosition> expectedParsed = Optional.of(expected);
    assertEquals(expectedParsed, parsed);

    final SeekPosition secondExpected = SeekPosition.of(RELATIVE, JUMP_BACK_POSITION_MILLISECONDS);
    final Optional<SeekPosition> seekPositionParsed = SeekPosition.parse("-1:00");
    final Optional<SeekPosition> expectedSeekPosition = Optional.of(secondExpected);
    assertEquals(expectedSeekPosition, seekPositionParsed);

    final SeekPosition thirdExpected = SeekPosition.of(RELATIVE, -250L);
    final Optional<SeekPosition> secondParsed = SeekPosition.parse("-0.25");
    final Optional<SeekPosition> expectedSecondParsed = Optional.of(thirdExpected);
    assertEquals(expectedSecondParsed, secondParsed);
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
      "+360000",
      "-360000",
      "1:2:3:4",
    }
  )
  void shouldRefuseWhatIsNotATime(final String text) {
    final Optional<SeekPosition> parsed = SeekPosition.parse(text);
    assertEquals(Optional.empty(), parsed, text);
  }

  @Test
  void shouldRefuseANullText() {
    assertThrows(NullPointerException.class, () -> SeekPosition.parse(null));
  }

  @Test
  void shouldResolveAgainstTheCurrentPositionAndNeverBeforeTheStart() {
    final long resolved = SeekPosition.of(ABSOLUTE, MINUTES_POSITION_MILLISECONDS).resolve(5_000L);
    assertEquals(MINUTES_POSITION_MILLISECONDS, resolved);

    final long secondResolved = SeekPosition.of(RELATIVE, RELATIVE_POSITION_MILLISECONDS).resolve(5_000L);
    assertEquals(15_000L, secondResolved);

    final long thirdResolved = SeekPosition.of(RELATIVE, -10_000L).resolve(5_000L);
    assertEquals(0L, thirdResolved);
  }

  @Test
  void shouldFormatTimesAsPlayersReadThem() {
    final String formatted = SeekPosition.format(999L);
    assertEquals(ZERO_POSITION_TEXT, formatted);

    final String seekPositionFormatted = SeekPosition.format(MINUTES_POSITION_MILLISECONDS);
    assertEquals(MINUTES_POSITION_TEXT, seekPositionFormatted);

    final String secondFormatted = SeekPosition.format(3_599_000L);
    assertEquals("59:59", secondFormatted);

    final String thirdFormatted = SeekPosition.format(HOURS_POSITION_MILLISECONDS);
    assertEquals(HOURS_POSITION_TEXT, thirdFormatted);
  }

  @Test
  void shouldRefuseANegativeTimeToFormat() {
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> SeekPosition.format(-1L));

    final String message = exception.getMessage();
    assertEquals("Time -1 must not be negative!", message);
  }

  @Test
  void shouldFormatTheStartOfAVideo() {
    final String formatted = SeekPosition.format(0L);
    assertEquals(ZERO_POSITION_TEXT, formatted);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final SeekPosition position = SeekPosition.of(RELATIVE, RELATIVE_POSITION_MILLISECONDS);
    final SeekPosition equalPosition = SeekPosition.of(RELATIVE, RELATIVE_POSITION_MILLISECONDS);
    final SeekPosition otherStartingPoint = SeekPosition.of(ABSOLUTE, RELATIVE_POSITION_MILLISECONDS);
    final SeekPosition otherTime = SeekPosition.of(RELATIVE, -RELATIVE_POSITION_MILLISECONDS);
    EqualityAssertions.assertEqualityContract(position, equalPosition, otherStartingPoint, otherTime);
  }

  @Test
  void shouldHashTheStartingPointThenTheTime() {
    final SeekPosition position = SeekPosition.of(RELATIVE, RELATIVE_POSITION_MILLISECONDS);

    final int hash = position.hashCode();
    final int expected = Objects.hash(true, RELATIVE_POSITION_MILLISECONDS);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderTheStartingPointAndTheTime() {
    final SeekPosition position = SeekPosition.of(ABSOLUTE, MINUTES_POSITION_MILLISECONDS);
    final String text = position.toString();
    assertEquals("SeekPosition{relative=false, milliseconds=90000}", text);
  }

  @Test
  void shouldKeepItsStartingPointAndTime() {
    final SeekPosition relativePosition = SeekPosition.of(RELATIVE, JUMP_BACK_POSITION_MILLISECONDS);
    final boolean relative = relativePosition.isRelative();
    final long milliseconds = relativePosition.getMilliseconds();
    assertTrue(relative);
    assertEquals(JUMP_BACK_POSITION_MILLISECONDS, milliseconds);

    final SeekPosition absolutePosition = SeekPosition.of(ABSOLUTE, 0L);
    final boolean absoluteRelative = absolutePosition.isRelative();
    final long absoluteMilliseconds = absolutePosition.getMilliseconds();
    assertFalse(absoluteRelative);
    assertEquals(0L, absoluteMilliseconds);
  }
}
