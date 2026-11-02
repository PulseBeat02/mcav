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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

final class SeekPositionPropertyTest {

  private static final long MILLISECONDS_PER_SECOND = 1_000L;

  @Property
  void shouldReadBackEveryFormattedTime(@ForAll @LongRange(min = 0, max = SeekPosition.MAXIMUM_MILLISECONDS) final long milliseconds) {
    final long whole = milliseconds - (milliseconds % MILLISECONDS_PER_SECOND);
    final SeekPosition absolute = SeekPosition.parse(SeekPosition.format(whole));
    final SeekPosition forward = SeekPosition.parse("+" + SeekPosition.format(whole));
    final SeekPosition back = SeekPosition.parse("-" + SeekPosition.format(whole));

    final SeekPosition expected = new SeekPosition(false, whole);
    assertEquals(expected, absolute);

    final SeekPosition secondExpected = new SeekPosition(true, whole);
    assertEquals(secondExpected, forward);

    final SeekPosition thirdExpected = new SeekPosition(true, -whole);
    assertEquals(thirdExpected, back);
  }

  @Property
  void shouldReadSecondsWithTheirMilliseconds(
    @ForAll @LongRange(min = 0, max = SeekPosition.MAXIMUM_MILLISECONDS) final long milliseconds
  ) {
    final String text = String.format(
      Locale.ROOT,
      "%d.%03d",
      milliseconds / MILLISECONDS_PER_SECOND,
      milliseconds % MILLISECONDS_PER_SECOND
    );
    final SeekPosition position = SeekPosition.parse(text);
    assertNotNull(position, text);

    final long positionMilliseconds = position.milliseconds();
    assertEquals(milliseconds, positionMilliseconds);
  }

  @Property
  void shouldNeverResolveBeforeTheStart(
    @ForAll @LongRange(min = -SeekPosition.MAXIMUM_MILLISECONDS, max = SeekPosition.MAXIMUM_MILLISECONDS) final long milliseconds,
    @ForAll @LongRange(min = 0, max = SeekPosition.MAXIMUM_MILLISECONDS) final long current
  ) {
    final long target = new SeekPosition(true, milliseconds).resolve(current);
    assertTrue(target >= 0);

    final long expected = Math.max(0, current + milliseconds);
    assertEquals(expected, target);
  }
}
