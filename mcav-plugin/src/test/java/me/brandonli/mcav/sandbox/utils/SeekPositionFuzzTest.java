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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the position a player types in {@code /mcav video seek}: whatever the text, it is refused or read as a time
 * within the bounds, which then resolves to a position at or after the start of the video.
 */
@Tag("fuzz")
final class SeekPositionFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void everyTypedPositionIsBoundedOrRefused(final FuzzedDataProvider data) {
    final long current = data.consumeLong(0, SeekPosition.MAX_MILLIS);
    final String text = data.consumeRemainingAsString();
    final SeekPosition position = SeekPosition.parse(text);
    if (position == null) {
      return;
    }
    assertTrue(Math.abs(position.millis()) <= SeekPosition.MAX_MILLIS, text);
    assertTrue(position.relative() || position.millis() >= 0, text);
    assertTrue(position.resolve(current) >= 0, text);
  }
}
