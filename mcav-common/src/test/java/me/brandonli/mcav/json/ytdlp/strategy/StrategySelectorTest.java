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
package me.brandonli.mcav.json.ytdlp.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link StrategySelector}, {@link StrategySelectorImpl} and {@link NoMatchingFormatException}.
 */
final class StrategySelectorTest {

  private static final String STREAMS =
    "\"formats\": [{\"format_id\": \"audio\", \"audio_ext\": \"webm\", \"video_ext\": \"none\"}, {\"format_id\": \"video\", \"audio_ext\": \"none\", \"video_ext\": \"mp4\"}]";

  private static URLParseDump dump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, URLParseDump.class);
  }

  private static Format firstFormat(final URLParseDump dump) {
    final List<Format> formats = dump.formats;
    return formats == null ? null : formats.getFirst();
  }

  @Test
  void createsASelectorFromTwoStrategies() {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final FormatStrategy audio = selector.getAudioStrategy();
    final FormatStrategy video = selector.getVideoStrategy();
    assertInstanceOf(StrategySelectorImpl.class, selector);
    assertSame(FormatStrategy.FIRST_AUDIO, audio);
    assertSame(FormatStrategy.BEST_QUALITY_VIDEO, video);
  }

  @Test
  void rejectsNullStrategies() {
    assertThrows(NullPointerException.class, () -> StrategySelector.of(null, FormatStrategy.FIRST_VIDEO));
    assertThrows(NullPointerException.class, () -> StrategySelector.of(FormatStrategy.FIRST_AUDIO, null));
  }

  @Test
  void selectsTheAudioStreamWithTheAudioStrategyAndTheVideoStreamWithTheVideoStrategy() {
    final URLParseDump dump = dump("{" + STREAMS + "}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    final Format audio = selector.getAudioSource(dump);
    final Format video = selector.getVideoSource(dump);
    assertEquals("audio", audio.format_id);
    assertEquals("video", video.format_id);
  }

  @Test
  void usesExactlyTheStrategyItWasGiven() {
    final URLParseDump dump = dump("{" + STREAMS + "}");
    final FormatStrategy firstListed = candidate -> {
      final Format first = firstFormat(candidate);
      return Optional.ofNullable(first);
    };
    final StrategySelector selector = StrategySelector.of(firstListed, firstListed);
    final Format audio = selector.getAudioSource(dump);
    final Format video = selector.getVideoSource(dump);
    assertEquals("audio", audio.format_id);
    assertEquals("audio", video.format_id);
  }

  @Test
  void rejectsANullDump() {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    assertThrows(NullPointerException.class, () -> selector.getAudioSource(null));
    assertThrows(NullPointerException.class, () -> selector.getVideoSource(null));
  }

  @Test
  void namesTheTitleWhenNoStreamMatches() {
    final URLParseDump dump = dump("{\"title\": \"Example Video\", \"webpage_url\": \"https://example.com/watch\"}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream matches the strategy for Example Video", audioMessage);
    assertEquals("No video stream matches the strategy for Example Video", videoMessage);
  }

  @Test
  void namesThePageUrlWhenTheTitleIsUnknown() {
    final URLParseDump dump = dump("{\"webpage_url\": \"https://example.com/watch\"}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream matches the strategy for https://example.com/watch", audioMessage);
    assertEquals("No video stream matches the strategy for https://example.com/watch", videoMessage);
  }

  @Test
  void fallsBackToAGenericDescription() {
    final URLParseDump dump = new URLParseDump();
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream matches the strategy for the media", audioMessage);
    assertEquals("No video stream matches the strategy for the media", videoMessage);
  }
}
