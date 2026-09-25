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
package me.brandonli.mcav.sandbox.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SuggestionProvider}.
 */
final class SuggestionProviderTest {

  private final SuggestionProvider provider = new SuggestionProvider();

  private static void assertSuggests(final List<String> expected, final Stream<String> suggestions) {
    final List<String> values = suggestions.toList();
    assertEquals(expected, values);
  }

  @Test
  void suggestsMapIds() {
    final Stream<String> suggestions = this.provider.suggestId();
    final List<String> expected = List.of("0", "5", "10", "100", "1000", "10000", "100000");
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsScreenDimensions() {
    final Stream<String> suggestions = this.provider.suggestDimensions();
    final List<String> expected = List.of("4x4", "5x5", "16x9", "16x16", "32x18");
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsResolutions() {
    final Stream<String> suggestions = this.provider.suggestResolutions();
    final List<String> expected = List.of("512x512", "640x640", "1280x720", "1280x1280", "1920x1080");
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsChatResolutions() {
    final Stream<String> suggestions = this.provider.suggestChatResolutions();
    final List<String> expected = List.of("8x8", "16x16", "32x32");
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsChatCharacters() {
    final Stream<String> suggestions = this.provider.suggestChatCharacters();
    final List<String> expected = List.of(Characters.FULL_CHARACTER, Characters.BLACK_CIRCLE, Characters.SMALL_BLACK_SQUARE);
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsFrameIntervals() {
    final Stream<String> suggestions = this.provider.suggestNth();
    final List<String> expected = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    assertSuggests(expected, suggestions);
  }

  @Test
  void suggestsFrameRates() {
    final Stream<String> suggestions = this.provider.suggestTargetFps();
    final List<String> expected = List.of("20", "30", "40", "50", "60", "70", "80", "90", "100", "120", "144", "240");
    assertSuggests(expected, suggestions);
  }
}
