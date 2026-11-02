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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import me.brandonli.mcav.utils.immutable.Pair;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class SizeArgumentsFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldBoundOrRefuseEveryTypedSize(final byte[] input) {
    final String typed = new String(input, StandardCharsets.UTF_8);
    this.assertBoundedOrRefused(typed, ArgumentUtils::parseDimensions, ArgumentUtils.MAXIMUM_SIDE);
    this.assertBoundedOrRefused(typed, ArgumentUtils::parseScreenDimensions, ArgumentUtils.MAXIMUM_SCREEN_SIDE);
  }

  private void assertBoundedOrRefused(final String typed, final Function<String, Pair<Integer, Integer>> parser, final int maximum) {
    final Pair<Integer, Integer> size;
    try {
      size = parser.apply(typed);
    } catch (final IllegalArgumentException exception) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final boolean bounded = width >= 1 && width <= maximum && height >= 1 && height <= maximum;
    assertTrue(bounded, () -> "'" + typed + "' became " + width + "x" + height);
  }
}
