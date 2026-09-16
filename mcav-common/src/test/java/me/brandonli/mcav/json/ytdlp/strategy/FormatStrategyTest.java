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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FormatStrategy}.
 */
final class FormatStrategyTest {

  private static final List<FormatStrategy> ALL_STRATEGIES = List.of(
    FormatStrategy.LOWEST_QUALITY_AUDIO,
    FormatStrategy.LOWEST_QUALITY_VIDEO,
    FormatStrategy.BEST_QUALITY_AUDIO,
    FormatStrategy.BEST_QUALITY_VIDEO,
    FormatStrategy.FIRST_AUDIO,
    FormatStrategy.FIRST_VIDEO,
    FormatStrategy.PREFER_WEBM_AUDIO,
    FormatStrategy.PREFER_MP4_VIDEO
  );

  private static URLParseDump dump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, URLParseDump.class);
  }

  private static URLParseDump formats(final String... formats) {
    final String joined = String.join(", ", formats);
    return dump("{\"formats\": [" + joined + "]}");
  }

  private static Format format(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, Format.class);
  }

  private static String audio(final String id, final double quality, final String extension, final String protocol) {
    return "{\"format_id\": \"%s\", \"quality\": %s, \"audio_ext\": \"%s\", \"video_ext\": \"none\", \"protocol\": \"%s\"}".formatted(
        id,
        quality,
        extension,
        protocol
      );
  }

  private static String video(final String id, final double quality, final String extension, final String protocol) {
    return "{\"format_id\": \"%s\", \"quality\": %s, \"audio_ext\": \"none\", \"video_ext\": \"%s\", \"protocol\": \"%s\"}".formatted(
        id,
        quality,
        extension,
        protocol
      );
  }

  private static String select(final FormatStrategy strategy, final URLParseDump dump) {
    final Optional<Format> selected = strategy.select(dump);
    final Format format = selected.orElseThrow();
    return format.format_id;
  }

  @Test
  void selectsNothingWithoutStreams() {
    final URLParseDump missing = dump("{}");
    final URLParseDump empty = formats();
    final URLParseDump onlyNulls = formats("null", "null");
    final URLParseDump storyboards = formats("{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\", \"quality\": 99}");
    for (final FormatStrategy strategy : ALL_STRATEGIES) {
      final Optional<Format> fromMissing = strategy.select(missing);
      final Optional<Format> fromEmpty = strategy.select(empty);
      final Optional<Format> fromNulls = strategy.select(onlyNulls);
      final Optional<Format> fromStoryboards = strategy.select(storyboards);
      final boolean fromMissingEmpty = fromMissing.isEmpty();
      assertTrue(fromMissingEmpty);
      final boolean fromEmptyEmpty = fromEmpty.isEmpty();
      assertTrue(fromEmptyEmpty);
      final boolean fromNullsEmpty = fromNulls.isEmpty();
      assertTrue(fromNullsEmpty);
      final boolean fromStoryboardsEmpty = fromStoryboards.isEmpty();
      assertTrue(fromStoryboardsEmpty);
    }
  }

  @Test
  void rejectsANullDump() {
    for (final FormatStrategy strategy : ALL_STRATEGIES) {
      assertThrows(NullPointerException.class, () -> strategy.select(null));
    }
  }

  @Test
  void skipsNullEntries() {
    final String audioStream = audio("a", 1, "webm", "https");
    final String videoStream = video("v", 5, "mp4", "https");
    final URLParseDump dump = formats("null", audioStream, "null", videoStream, "null");
    final String firstAudio = select(FormatStrategy.FIRST_AUDIO, dump);
    final String firstVideo = select(FormatStrategy.FIRST_VIDEO, dump);
    final String bestAudio = select(FormatStrategy.BEST_QUALITY_AUDIO, dump);
    final String lowestVideo = select(FormatStrategy.LOWEST_QUALITY_VIDEO, dump);
    final String webm = select(FormatStrategy.PREFER_WEBM_AUDIO, dump);
    final String mp4 = select(FormatStrategy.PREFER_MP4_VIDEO, dump);
    assertEquals("a", firstAudio);
    assertEquals("v", firstVideo);
    assertEquals("a", bestAudio);
    assertEquals("v", lowestVideo);
    assertEquals("a", webm);
    assertEquals("v", mp4);
  }

  @Test
  void picksTheBestAndLowestQualityOfTheMatchingKind() {
    final String storyboard = "{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\", \"quality\": 100}";
    final String video5 = video("v5", 5, "mp4", "https");
    final String audio2 = audio("a2", 2, "m4a", "https");
    final String video9 = video("v9", 9, "webm", "https");
    final String audio3 = audio("a3", 3, "webm", "https");
    final String hlsVideo7 = video("v7", 7, "mp4", "m3u8_native");
    final String audio1 = audio("a1", 1, "m4a", "https");
    final String negativeVideo = video("v-1", -1, "mp4", "https");
    final URLParseDump dump = formats(storyboard, video5, audio2, video9, audio3, hlsVideo7, audio1, negativeVideo);
    final String bestAudio = select(FormatStrategy.BEST_QUALITY_AUDIO, dump);
    final String lowestAudio = select(FormatStrategy.LOWEST_QUALITY_AUDIO, dump);
    final String bestVideo = select(FormatStrategy.BEST_QUALITY_VIDEO, dump);
    final String lowestVideo = select(FormatStrategy.LOWEST_QUALITY_VIDEO, dump);
    assertEquals("a3", bestAudio);
    assertEquals("a1", lowestAudio);
    assertEquals("v9", bestVideo);
    assertEquals("v-1", lowestVideo);
  }

  @Test
  void breaksQualityTiesInTheOrderYtdlpRanksStreams() {
    // yt-dlp lists streams from worst to best, so among equal qualities the later stream is the better one
    final String avc1Low = video("avc1-low", 1, "mp4", "https");
    final String vp9Low = video("vp9-low", 1, "webm", "https");
    final String m4a = audio("m4a", 3, "m4a", "https");
    final String avc1High = video("avc1-high", 9, "mp4", "https");
    final String opus = audio("opus", 3, "webm", "https");
    final String vp9High = video("vp9-high", 9, "webm", "https");
    final URLParseDump dump = formats(avc1Low, vp9Low, m4a, avc1High, opus, vp9High);
    final String bestVideo = select(FormatStrategy.BEST_QUALITY_VIDEO, dump);
    final String lowestVideo = select(FormatStrategy.LOWEST_QUALITY_VIDEO, dump);
    final String bestAudio = select(FormatStrategy.BEST_QUALITY_AUDIO, dump);
    final String lowestAudio = select(FormatStrategy.LOWEST_QUALITY_AUDIO, dump);
    assertEquals("vp9-high", bestVideo);
    assertEquals("avc1-low", lowestVideo);
    assertEquals("opus", bestAudio);
    assertEquals("m4a", lowestAudio);
  }

  @Test
  void fallsBackToTheYtdlpOrderWhenNoQualityIsReported() {
    final URLParseDump dump = formats(
      "{\"format_id\": \"worst\", \"video_ext\": \"mp4\"}",
      "{\"format_id\": \"middle\", \"video_ext\": \"mp4\"}",
      "{\"format_id\": \"best\", \"video_ext\": \"mp4\"}"
    );
    final String bestVideo = select(FormatStrategy.BEST_QUALITY_VIDEO, dump);
    final String lowestVideo = select(FormatStrategy.LOWEST_QUALITY_VIDEO, dump);
    assertEquals("best", bestVideo);
    assertEquals("worst", lowestVideo);
  }

  @Test
  void picksTheFirstStreamOfTheMatchingKind() {
    final String storyboard = "{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\"}";
    final String video1Stream = video("v1", 9, "mp4", "m3u8_native");
    final String audio1Stream = audio("a1", 1, "m4a", "https");
    final String video2Stream = video("v2", 1, "webm", "https");
    final String audio2Stream = audio("a2", 3, "webm", "https");
    final URLParseDump dump = formats(storyboard, video1Stream, audio1Stream, video2Stream, audio2Stream);
    final String firstAudio = select(FormatStrategy.FIRST_AUDIO, dump);
    final String firstVideo = select(FormatStrategy.FIRST_VIDEO, dump);
    assertEquals("a1", firstAudio);
    assertEquals("v1", firstVideo);
  }

  @Test
  void prefersWebmAudioServedOverHttps() {
    final String m4aHttps = audio("m4a-https", 3, "m4a", "https");
    final String webmHls = audio("webm-hls", 3, "webm", "m3u8_native");
    final String webmHttp = audio("webm-http", 3, "webm", "http");
    final String webmVideo = video("video-webm", 3, "webm", "https");
    final String webmHttps = audio("webm-https", 1, "webm", "https");
    final String webmHttpsLater = audio("webm-https-later", 3, "webm", "https");
    final URLParseDump dump = formats(m4aHttps, webmHls, webmHttp, webmVideo, webmHttps, webmHttpsLater);
    final URLParseDump withoutMatch = formats(m4aHttps, webmHls);
    final String selected = select(FormatStrategy.PREFER_WEBM_AUDIO, dump);
    final Optional<Format> none = FormatStrategy.PREFER_WEBM_AUDIO.select(withoutMatch);
    assertEquals("webm-https", selected);
    final boolean noneEmpty = none.isEmpty();
    assertTrue(noneEmpty);
  }

  @Test
  void prefersMp4VideoServedOverHttps() {
    final String webmHttps = video("webm-https", 9, "webm", "https");
    final String mp4Dash = video("mp4-dash", 9, "mp4", "http_dash_segments");
    final String mp4Audio = audio("audio-mp4", 9, "mp4", "https");
    final String mp4Https = video("mp4-https", 5, "mp4", "https");
    final String mp4HttpsLater = video("mp4-https-later", 9, "mp4", "https");
    final URLParseDump dump = formats(webmHttps, mp4Dash, mp4Audio, mp4Https, mp4HttpsLater);
    final URLParseDump withoutMatch = formats(webmHttps);
    final String selected = select(FormatStrategy.PREFER_MP4_VIDEO, dump);
    final Optional<Format> none = FormatStrategy.PREFER_MP4_VIDEO.select(withoutMatch);
    assertEquals("mp4-https", selected);
    final boolean noneEmpty = none.isEmpty();
    assertTrue(noneEmpty);
  }

  @Test
  void detectsAudioOnlyStreams() {
    final Format audioOnly = format("{\"audio_ext\": \"webm\", \"video_ext\": \"none\"}");
    final Format noAudio = format("{\"audio_ext\": \"none\", \"video_ext\": \"mp4\"}");
    final Format unknown = format("{}");
    final boolean audioOnlyHasAudio = FormatStrategy.hasAudio(audioOnly);
    final boolean noAudioHasAudio = FormatStrategy.hasAudio(noAudio);
    final boolean unknownHasAudio = FormatStrategy.hasAudio(unknown);
    assertTrue(audioOnlyHasAudio);
    assertFalse(noAudioHasAudio);
    assertFalse(unknownHasAudio);
    assertThrows(NullPointerException.class, () -> FormatStrategy.hasAudio(null));
  }

  @Test
  void detectsVideoStreams() {
    final Format video = format("{\"audio_ext\": \"none\", \"video_ext\": \"mp4\"}");
    final Format noVideo = format("{\"audio_ext\": \"webm\", \"video_ext\": \"none\"}");
    final Format unknown = format("{}");
    final boolean videoHasVideo = FormatStrategy.hasVideo(video);
    final boolean noVideoHasVideo = FormatStrategy.hasVideo(noVideo);
    final boolean unknownHasVideo = FormatStrategy.hasVideo(unknown);
    assertTrue(videoHasVideo);
    assertFalse(noVideoHasVideo);
    assertFalse(unknownHasVideo);
    assertThrows(NullPointerException.class, () -> FormatStrategy.hasVideo(null));
  }

  @Test
  void detectsPlainHttpsStreams() {
    final Format https = format("{\"protocol\": \"https\"}");
    final Format http = format("{\"protocol\": \"http\"}");
    final Format hls = format("{\"protocol\": \"m3u8_native\"}");
    final Format unknown = format("{}");
    final boolean httpsIsHttps = FormatStrategy.isHttps(https);
    final boolean httpIsHttps = FormatStrategy.isHttps(http);
    final boolean hlsIsHttps = FormatStrategy.isHttps(hls);
    final boolean unknownIsHttps = FormatStrategy.isHttps(unknown);
    assertTrue(httpsIsHttps);
    assertFalse(httpIsHttps);
    assertFalse(hlsIsHttps);
    assertFalse(unknownIsHttps);
    assertThrows(NullPointerException.class, () -> FormatStrategy.isHttps(null));
  }
}
