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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

final class SeekPositionPropertyTest {

  @Property
  void readsBackEveryFormattedTime(@ForAll @LongRange(min = 0, max = SeekPosition.MAX_MILLIS) final long millis) {
    final long whole = millis - (millis % 1_000L);
    final SeekPosition absolute = SeekPosition.parse(SeekPosition.format(whole));
    final SeekPosition forward = SeekPosition.parse("+" + SeekPosition.format(whole));
    final SeekPosition back = SeekPosition.parse("-" + SeekPosition.format(whole));
    assertEquals(new SeekPosition(false, whole), absolute);
    assertEquals(new SeekPosition(true, whole), forward);
    assertEquals(new SeekPosition(true, -whole), back);
  }

  @Property
  void readsSecondsWithTheirMilliseconds(@ForAll @LongRange(min = 0, max = SeekPosition.MAX_MILLIS) final long millis) {
    final String text = String.format(Locale.ROOT, "%d.%03d", millis / 1_000L, millis % 1_000L);
    final SeekPosition position = SeekPosition.parse(text);
    assertNotNull(position, text);
    assertEquals(millis, position.millis());
  }

  @Property
  void neverResolvesBeforeTheStart(
    @ForAll @LongRange(min = -SeekPosition.MAX_MILLIS, max = SeekPosition.MAX_MILLIS) final long millis,
    @ForAll @LongRange(min = 0, max = SeekPosition.MAX_MILLIS) final long current
  ) {
    final long target = new SeekPosition(true, millis).resolve(current);
    assertTrue(target >= 0);
    assertEquals(Math.max(0, current + millis), target);
  }
}
