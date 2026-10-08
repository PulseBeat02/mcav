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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import me.brandonli.mcav.utils.immutable.Pair;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the sizes players type into the display commands, a resolution such as {@code 1920x1080} or a wall such as
 * {@code 5x3}: whatever the text, it either becomes a size within the bounds the commands document, which keep the main
 * thread and the memory of the server safe, or is refused with the {@link IllegalArgumentException} the commands turn
 * into a message. The seeds are the sizes of the tests and the documentation.
 */
@Tag("fuzz")
final class SizeArgumentsFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void everyTypedSizeIsBoundedOrRefused(final byte[] input) {
    final String typed = new String(input, StandardCharsets.UTF_8);
    assertBoundedOrRefused(typed, false, ArgumentUtils.MAX_SIDE);
    assertBoundedOrRefused(typed, true, ArgumentUtils.MAX_SCREEN_SIDE);
  }

  private static void assertBoundedOrRefused(final String typed, final boolean wall, final int maximum) {
    final Pair<Integer, Integer> size;
    try {
      size = wall ? ArgumentUtils.parseScreenDimensions(typed) : ArgumentUtils.parseDimensions(typed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final boolean bounded = width >= 1 && width <= maximum && height >= 1 && height <= maximum;
    assertTrue(bounded, () -> "'" + typed + "' became " + width + "x" + height);
  }
}
