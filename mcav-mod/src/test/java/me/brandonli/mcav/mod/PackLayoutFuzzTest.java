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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class PackLayoutFuzzTest {

  private static final int MOST_SCREENS = 8;
  private static final int MOST_SLOTS = 8;

  private static final long LARGEST_STREAM = 0xFFFF_FFFFL;
  private static final long LARGEST_STREAM_TRIED = 0x1_FFFF_FFFFL;

  private static final String UNSIGNED_SUFFIX = "u";
  private static final String SEPARATOR = ", ";

  @FuzzTest(maxDuration = "30s")
  void shouldParseOnlyLayoutsTheServerCouldGenerate(final FuzzedDataProvider data) {
    final int screens = data.consumeInt(0, MOST_SCREENS + 1);
    final long[] streams = this.consumeLongs(data, screens, LARGEST_STREAM_TRIED);
    final long[] slots = this.consumeLongs(data, screens, MOST_SLOTS + 1);
    final long[] firstSlots = this.consumeLongs(data, screens, MOST_SCREENS * MOST_SLOTS + 1);
    final String streamsText = this.join(streams, UNSIGNED_SUFFIX);
    final String slotsText = this.join(slots, "");
    final String firstSlotsText = this.join(firstSlots, "");
    final String packConstants = PackLayoutTest.packConstants(streamsText, slotsText, firstSlotsText, screens);

    final Optional<PackLayout> layout = PackLayout.parse(packConstants);
    final boolean valid = this.isValid(streams, slots, firstSlots);
    assertEquals(valid, layout.isPresent());

    layout.ifPresent(parsed -> this.assertLayout(parsed, streams, slots));
  }

  @FuzzTest(maxDuration = "30s")
  void shouldParseAnyTextWithoutFailing(final FuzzedDataProvider data) {
    final String packConstants = data.consumeRemainingAsString();

    final Optional<PackLayout> layout = PackLayout.parse(packConstants);
    final boolean screensInRange = layout.map(PackLayout::screens).map(this::isScreenCountInRange).orElse(true);
    assertTrue(screensInRange);
  }

  private long[] consumeLongs(final FuzzedDataProvider data, final int count, final long maximum) {
    final long[] values = new long[count];
    for (int index = 0; index < count; index++) {
      values[index] = data.consumeLong(0, maximum);
    }

    return values;
  }

  private String join(final long[] values, final String suffix) {
    return Arrays.stream(values)
      .mapToObj(value -> value + suffix)
      .collect(Collectors.joining(SEPARATOR));
  }

  private boolean isValid(final long[] streams, final long[] slots, final long[] firstSlots) {
    final boolean screensInRange = this.isScreenCountInRange(streams.length);
    final boolean streamsInRange = Arrays.stream(streams).allMatch(stream -> stream <= LARGEST_STREAM);
    return screensInRange && streamsInRange && this.areBackToBack(slots, firstSlots);
  }

  private boolean isScreenCountInRange(final int screens) {
    return screens >= 1 && screens <= MOST_SCREENS;
  }

  private boolean areBackToBack(final long[] slots, final long[] firstSlots) {
    long total = 0;
    for (int screen = 0; screen < slots.length; screen++) {
      if (slots[screen] < 1 || slots[screen] > MOST_SLOTS || firstSlots[screen] != total) {
        return false;
      }

      total += slots[screen];
    }

    return true;
  }

  private void assertLayout(final PackLayout layout, final long[] streams, final long[] slots) {
    final int screens = layout.screens();
    final long totalSlots = layout.totalSlots();
    final long expectedTotalSlots = Arrays.stream(slots).sum();
    assertEquals(streams.length, screens);
    assertEquals(expectedTotalSlots, totalSlots);

    for (int screen = 0; screen < streams.length; screen++) {
      final int found = layout.screenOf(streams[screen]);
      assertTrue(found >= 0 && found <= screen);
    }
  }
}
