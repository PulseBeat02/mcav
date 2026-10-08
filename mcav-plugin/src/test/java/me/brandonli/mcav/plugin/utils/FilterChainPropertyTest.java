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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

final class FilterChainPropertyTest {

  private static final Path NO_OVERLAYS = Path.of("no-overlays-here");

  @Provide
  Arbitrary<String> filters() {
    return Arbitraries.oneOf(
      Arbitraries.of("grayscale", "invert", "fps"),
      Arbitraries.integers()
        .between(1, FilterChain.MAX_BLUR)
        .map(radius -> "blur=" + radius),
      Arbitraries.integers()
        .between(1, FilterChain.MAX_BILATERAL)
        .map(diameter -> "bilateral=" + diameter),
      Arbitraries.integers()
        .between(0, 255)
        .map(level -> "threshold=" + level),
      Arbitraries.integers()
        .between(-255, 255)
        .map(brightness -> "luminance=1.25:" + brightness),
      Arbitraries.of("h", "v", "hv").map(direction -> "flip=" + direction),
      Arbitraries.of("90", "180", "270").map(angle -> "rotate=" + angle),
      Arbitraries.integers()
        .between(0, 99)
        .map(left -> String.format(Locale.ROOT, "crop=%d:0:%d:100", left, 100 - left)),
      Arbitraries.integers()
        .between(1, FilterChain.MAX_MORPHOLOGY)
        .map(kernel -> "dilate=" + kernel),
      Arbitraries.strings()
        .withChars("abcXYZ019 .!?'()+-")
        .ofMinLength(1)
        .ofMaxLength(FilterChain.MAX_TEXT)
        .map(text -> "text=" + text)
    );
  }

  @Property
  void parsesEveryChainOfAllowedFilters(@ForAll final List<@From("filters") String> specs) {
    final List<String> chain = specs.subList(0, Math.min(specs.size(), FilterChain.MAX_FILTERS));
    final String text = String.join(",", chain);
    if (text.length() > FilterChain.MAX_LENGTH) {
      return;
    }
    assertEquals(chain.size(), FilterChain.parse(text, NO_OVERLAYS, true).create().size(), text);
  }

  @Property
  void refusesAnythingElseWithAReason(@ForAll final String text) {
    try {
      final FilterChain chain = FilterChain.parse(text, NO_OVERLAYS, true);
      assertTrue(chain.create().size() <= FilterChain.MAX_FILTERS, text);
    } catch (final IllegalArgumentException refused) {
      assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank(), text);
    }
  }
}
