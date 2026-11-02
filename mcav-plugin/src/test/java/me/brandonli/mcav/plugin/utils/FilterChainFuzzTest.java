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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class FilterChainFuzzTest {

  private static final Path NO_OVERLAYS = Path.of("no-overlays-here");

  @FuzzTest(maxDuration = "30s")
  void shouldBoundOrRefuseEveryTypedChain(final FuzzedDataProvider data) {
    final MediaKind kind = data.consumeBoolean() ? MediaKind.VIDEO : MediaKind.IMAGE;
    final String text = data.consumeRemainingAsString();
    final FilterChain chain;
    try {
      chain = FilterChain.parse(text, NO_OVERLAYS, kind);
    } catch (final IllegalArgumentException exception) {
      final boolean thirdMatches = exception.getMessage() != null && !exception.getMessage().isBlank();
      assertTrue(thirdMatches, text);

      return;
    }

    final boolean matches = text.length() <= FilterChain.MAXIMUM_LENGTH;
    assertTrue(matches, text);

    final boolean secondMatches = chain.createFilters().size() <= FilterChain.MAXIMUM_FILTERS;
    assertTrue(secondMatches, text);
  }
}
