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
package me.brandonli.mcav.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.junit.jupiter.api.Test;

final class SuggestionProviderTest {

  private final SuggestionProvider provider = new SuggestionProvider();

  @Test
  void shouldSuggestMapIds() {
    final Stream<String> suggestions = this.provider.suggestId();
    final List<String> expected = List.of("0", "5", "10", "100", "1000", "10000", "100000");
    this.assertSuggests(expected, suggestions);
  }

  private void assertSuggests(final List<String> expected, final Stream<String> suggestions) {
    final List<String> values = suggestions.toList();
    assertEquals(expected, values);
  }

  @Test
  void shouldSuggestScreenDimensions() {
    final Stream<String> suggestions = this.provider.suggestDimensions();
    final List<String> expected = List.of("4x4", "5x5", "16x9", "16x16", "32x18");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestResolutions() {
    final Stream<String> suggestions = this.provider.suggestResolutions();
    final List<String> expected = List.of("512x512", "640x640", "1280x720", "1280x1280", "1920x1080");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestChatResolutions() {
    final Stream<String> suggestions = this.provider.suggestChatResolutions();
    final List<String> expected = List.of("8x8", "16x16", "32x32");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestScoreboardResolutions() {
    final Stream<String> suggestions = this.provider.suggestScoreboardResolutions();
    final List<String> expected = List.of("8x8", "16x9", "15x15", "24x15");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestChatCharactersThatAQuotedArgumentReadsWhole() throws CommandSyntaxException {
    final Stream<String> suggestions = this.provider.suggestChatCharacters();
    final List<String> values = suggestions.toList();
    final List<String> characters = new ArrayList<>();
    for (final String value : values) {
      final String character = this.readAsQuotedArgument(value);
      characters.add(character);
    }

    final List<String> expected = List.of(Characters.FULL_CHARACTER, Characters.BLACK_CIRCLE, Characters.SMALL_BLACK_SQUARE);
    assertEquals(expected, characters);
  }

  private String readAsQuotedArgument(final String suggestion) throws CommandSyntaxException {
    final StringReader reader = new StringReader(suggestion);
    final StringArgumentType argument = StringArgumentType.string();
    final String character = argument.parse(reader);
    final boolean wholeSuggestionRead = !reader.canRead();
    assertTrue(wholeSuggestionRead, suggestion);
    return character;
  }

  @Test
  void shouldSuggestFrameIntervals() {
    final Stream<String> suggestions = this.provider.suggestNth();
    final List<String> expected = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestSeekPositions() {
    final Stream<String> suggestions = this.provider.suggestPositions();
    final List<String> expected = List.of("0", "+10", "-10", "+1:00", "-1:00", "1:30");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestVolumes() {
    final Stream<String> suggestions = this.provider.suggestVolumes();
    final List<String> expected = List.of("0", "25", "50", "75", "100", "150", "200");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestSpeeds() {
    final Stream<String> suggestions = this.provider.suggestSpeeds();
    final List<String> expected = List.of("0.5", "0.75", "1", "1.25", "1.5", "2");
    this.assertSuggests(expected, suggestions);
  }

  @Test
  void shouldSuggestFrameRates() {
    final Stream<String> suggestions = this.provider.suggestTargetFramesPerSecond();
    final List<String> expected = List.of("20", "30", "40", "50", "60", "70", "80", "90", "100", "120", "144", "240");
    this.assertSuggests(expected, suggestions);
  }
}
