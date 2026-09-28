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
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the {@code --filters} option a player types: whatever the text, it is refused with a reason, or it is a chain of
 * at most the allowed number of filters, which can all be made.
 */
@Tag("fuzz")
final class FilterChainFuzzTest {

  private static final Path NO_OVERLAYS = Path.of("no-overlays-here");

  @FuzzTest(maxDuration = "30s")
  void everyTypedChainIsBoundedOrRefused(final FuzzedDataProvider data) {
    final boolean video = data.consumeBoolean();
    final String text = data.consumeRemainingAsString();
    final FilterChain chain;
    try {
      chain = FilterChain.parse(text, NO_OVERLAYS, video);
    } catch (final IllegalArgumentException refused) {
      assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank(), text);
      return;
    }
    assertTrue(text.length() <= FilterChain.MAX_LENGTH, text);
    assertTrue(chain.create().size() <= FilterChain.MAX_FILTERS, text);
  }
}
