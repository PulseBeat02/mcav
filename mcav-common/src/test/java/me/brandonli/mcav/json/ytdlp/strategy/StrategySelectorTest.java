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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.junit.jupiter.api.Test;

final class StrategySelectorTest {

  private static final String STREAMS =
    "\"formats\": [{\"format_id\": \"audio\", \"audio_ext\": \"webm\", \"video_ext\": \"none\"}, {\"format_id\": \"video\", \"audio_ext\": \"none\", \"video_ext\": \"mp4\"}]";

  private static final String AUDIO_FORMAT_ID = "audio";

  private static final String PAGE_URL_FIELD = "\"webpage_url\": \"https://example.com/watch\"";

  private static UrlParseDump dump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  private static Format firstFormat(final UrlParseDump dump) {
    final List<Format> formats = dump.getFormats();
    return formats == null ? null : formats.getFirst();
  }

  @Test
  void shouldCreateASelectorFromTwoStrategies() {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final FormatStrategy audio = selector.getAudioStrategy();
    final FormatStrategy video = selector.getVideoStrategy();
    assertSame(FormatStrategy.FIRST_AUDIO, audio);
    assertSame(FormatStrategy.BEST_QUALITY_VIDEO, video);
  }

  @Test
  void shouldRejectNullStrategies() {
    assertThrows(NullPointerException.class, () -> StrategySelector.of(null, FormatStrategy.FIRST_VIDEO));
    assertThrows(NullPointerException.class, () -> StrategySelector.of(FormatStrategy.FIRST_AUDIO, null));
  }

  @Test
  void shouldSelectTheAudioStreamWithTheAudioStrategyAndTheVideoStreamWithTheVideoStrategy() {
    final UrlParseDump dump = dump("{" + STREAMS + "}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    final Format audio = selector.getAudioSource(dump);
    final Format video = selector.getVideoSource(dump);
    assertEquals(Optional.of(AUDIO_FORMAT_ID), audio.getFormatId());
    assertEquals(Optional.of("video"), video.getFormatId());
  }

  @Test
  void shouldUseExactlyTheStrategyItWasGiven() {
    final UrlParseDump dump = dump("{" + STREAMS + "}");
    final FormatStrategy firstListed = this::findTheFirstListed;
    final StrategySelector selector = StrategySelector.of(firstListed, firstListed);
    final Format audio = selector.getAudioSource(dump);
    final Format video = selector.getVideoSource(dump);
    assertEquals(Optional.of(AUDIO_FORMAT_ID), audio.getFormatId());
    assertEquals(Optional.of(AUDIO_FORMAT_ID), video.getFormatId());
  }

  private Optional<Format> findTheFirstListed(final UrlParseDump candidate) {
    final Format first = firstFormat(candidate);
    return Optional.ofNullable(first);
  }

  @Test
  void shouldRejectANullDump() {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    assertThrows(NullPointerException.class, () -> selector.getAudioSource(null));
    assertThrows(NullPointerException.class, () -> selector.getVideoSource(null));
  }

  @Test
  void shouldNameTheTitleWhenNoStreamMatches() {
    final UrlParseDump dump = dump("{\"title\": \"Example Video\", " + PAGE_URL_FIELD + "}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream of Example Video matches the strategy!", audioMessage);
    assertEquals("No video stream of Example Video matches the strategy!", videoMessage);
  }

  @Test
  void shouldNameThePageUrlWhenTheTitleIsUnknown() {
    final UrlParseDump dump = dump("{" + PAGE_URL_FIELD + "}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream of https://example.com/watch matches the strategy!", audioMessage);
    assertEquals("No video stream of https://example.com/watch matches the strategy!", videoMessage);
  }

  @Test
  void shouldFallBackToAGenericDescription() {
    final UrlParseDump dump = dump("{}");
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final NoMatchingFormatException audio = assertThrows(NoMatchingFormatException.class, () -> selector.getAudioSource(dump));
    final NoMatchingFormatException video = assertThrows(NoMatchingFormatException.class, () -> selector.getVideoSource(dump));
    final String audioMessage = audio.getMessage();
    final String videoMessage = video.getMessage();
    assertEquals("No audio stream of the media matches the strategy!", audioMessage);
    assertEquals("No video stream of the media matches the strategy!", videoMessage);
  }
}
