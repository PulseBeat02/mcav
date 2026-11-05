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
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.junit.jupiter.api.Test;

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

  private static final String HTTPS_PROTOCOL = "https";
  private static final String HLS_PROTOCOL = "m3u8_native";

  private static final String MP4_EXTENSION = "mp4";
  private static final String WEBM_EXTENSION = "webm";
  private static final String M4A_EXTENSION = "m4a";

  private static final String FIRST_AUDIO_ID = "a1";
  private static final String SECOND_AUDIO_ID = "a2";
  private static final String WEBM_HTTPS_ID = "webm-https";

  private static final String NULL_ENTRY = "null";
  private static final String EMPTY_JSON = "{}";
  private static final String AUDIO_ONLY_JSON = "{\"audio_ext\": \"webm\", \"video_ext\": \"none\"}";
  private static final String VIDEO_ONLY_JSON = "{\"audio_ext\": \"none\", \"video_ext\": \"mp4\"}";

  private static UrlParseDump dump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  private static UrlParseDump formats(final String... formats) {
    final String joined = String.join(", ", formats);
    return dump("{\"formats\": [%s]}".formatted(joined));
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

  private static String select(final FormatStrategy strategy, final UrlParseDump dump) {
    final Optional<Format> selected = strategy.select(dump);
    final Format format = selected.orElseThrow();
    final Optional<String> formatId = format.getFormatId();
    return formatId.orElseThrow();
  }

  @Test
  void shouldSelectNothingWithoutStreams() {
    final UrlParseDump missing = dump(EMPTY_JSON);
    final UrlParseDump empty = formats();
    final UrlParseDump onlyNulls = formats(NULL_ENTRY, NULL_ENTRY);
    final UrlParseDump storyboards = formats("{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\", \"quality\": 99}");
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
  void shouldRejectANullDump() {
    for (final FormatStrategy strategy : ALL_STRATEGIES) {
      assertThrows(NullPointerException.class, () -> strategy.select(null));
    }
  }

  @Test
  void shouldSkipNullEntries() {
    final String audioStream = audio("a", 1, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String videoStream = video("v", 5, MP4_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(NULL_ENTRY, audioStream, NULL_ENTRY, videoStream, NULL_ENTRY);
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
  void shouldPickTheBestAndLowestQualityOfTheMatchingKind() {
    final String storyboard = "{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\", \"quality\": 100}";
    final String video5 = video("v5", 5, MP4_EXTENSION, HTTPS_PROTOCOL);
    final String audio2 = audio(SECOND_AUDIO_ID, 2, M4A_EXTENSION, HTTPS_PROTOCOL);
    final String video9 = video("v9", 9, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String audio3 = audio("a3", 3, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String hlsVideo7 = video("v7", 7, MP4_EXTENSION, HLS_PROTOCOL);
    final String audio1 = audio(FIRST_AUDIO_ID, 1, M4A_EXTENSION, HTTPS_PROTOCOL);
    final String negativeVideo = video("v-1", -1, MP4_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(storyboard, video5, audio2, video9, audio3, hlsVideo7, audio1, negativeVideo);
    final String bestAudio = select(FormatStrategy.BEST_QUALITY_AUDIO, dump);
    final String lowestAudio = select(FormatStrategy.LOWEST_QUALITY_AUDIO, dump);
    final String bestVideo = select(FormatStrategy.BEST_QUALITY_VIDEO, dump);
    final String lowestVideo = select(FormatStrategy.LOWEST_QUALITY_VIDEO, dump);
    assertEquals("a3", bestAudio);
    assertEquals(FIRST_AUDIO_ID, lowestAudio);
    assertEquals("v9", bestVideo);
    assertEquals("v-1", lowestVideo);
  }

  @Test
  void shouldBreakQualityTiesInTheOrderYtdlpRanksStreams() {
    // yt-dlp lists streams from worst to best, so among equal qualities the later stream is the better one
    final String avc1Low = video("avc1-low", 1, MP4_EXTENSION, HTTPS_PROTOCOL);
    final String vp9Low = video("vp9-low", 1, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String m4a = audio("m4a", 3, M4A_EXTENSION, HTTPS_PROTOCOL);
    final String avc1High = video("avc1-high", 9, MP4_EXTENSION, HTTPS_PROTOCOL);
    final String opus = audio("opus", 3, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String vp9High = video("vp9-high", 9, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(avc1Low, vp9Low, m4a, avc1High, opus, vp9High);
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
  void shouldFallBackToTheYtdlpOrderWhenNoQualityIsReported() {
    final UrlParseDump dump = formats(
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
  void shouldPickTheFirstStreamOfTheMatchingKind() {
    final String storyboard = "{\"format_id\": \"sb0\", \"audio_ext\": \"none\", \"video_ext\": \"none\"}";
    final String video1Stream = video("v1", 9, MP4_EXTENSION, HLS_PROTOCOL);
    final String audio1Stream = audio(FIRST_AUDIO_ID, 1, M4A_EXTENSION, HTTPS_PROTOCOL);
    final String video2Stream = video("v2", 1, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String audio2Stream = audio(SECOND_AUDIO_ID, 3, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(storyboard, video1Stream, audio1Stream, video2Stream, audio2Stream);
    final String firstAudio = select(FormatStrategy.FIRST_AUDIO, dump);
    final String firstVideo = select(FormatStrategy.FIRST_VIDEO, dump);
    assertEquals(FIRST_AUDIO_ID, firstAudio);
    assertEquals("v1", firstVideo);
  }

  @Test
  void shouldPreferWebmAudioServedOverHttps() {
    final String m4aHttps = audio("m4a-https", 3, M4A_EXTENSION, HTTPS_PROTOCOL);
    final String webmHls = audio("webm-hls", 3, WEBM_EXTENSION, HLS_PROTOCOL);
    final String webmHttp = audio("webm-http", 3, WEBM_EXTENSION, "http");
    final String webmVideo = video("video-webm", 3, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String webmHttps = audio(WEBM_HTTPS_ID, 1, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String webmHttpsLater = audio("webm-https-later", 3, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(m4aHttps, webmHls, webmHttp, webmVideo, webmHttps, webmHttpsLater);
    final UrlParseDump withoutMatch = formats(m4aHttps, webmHls);
    final String selected = select(FormatStrategy.PREFER_WEBM_AUDIO, dump);
    final Optional<Format> none = FormatStrategy.PREFER_WEBM_AUDIO.select(withoutMatch);
    assertEquals(WEBM_HTTPS_ID, selected);

    final boolean noneEmpty = none.isEmpty();
    assertTrue(noneEmpty);
  }

  @Test
  void shouldPreferMp4VideoServedOverHttps() {
    final String webmHttps = video(WEBM_HTTPS_ID, 9, WEBM_EXTENSION, HTTPS_PROTOCOL);
    final String mp4Dash = video("mp4-dash", 9, MP4_EXTENSION, "http_dash_segments");
    final String mp4Audio = audio("audio-mp4", 9, MP4_EXTENSION, HTTPS_PROTOCOL);
    final String mp4Https = video("mp4-https", 5, MP4_EXTENSION, HTTPS_PROTOCOL);
    final String mp4HttpsLater = video("mp4-https-later", 9, MP4_EXTENSION, HTTPS_PROTOCOL);
    final UrlParseDump dump = formats(webmHttps, mp4Dash, mp4Audio, mp4Https, mp4HttpsLater);
    final UrlParseDump withoutMatch = formats(webmHttps);
    final String selected = select(FormatStrategy.PREFER_MP4_VIDEO, dump);
    final Optional<Format> none = FormatStrategy.PREFER_MP4_VIDEO.select(withoutMatch);
    assertEquals("mp4-https", selected);

    final boolean noneEmpty = none.isEmpty();
    assertTrue(noneEmpty);
  }

  @Test
  void shouldDetectAudioOnlyStreams() {
    final Format audioOnly = format(AUDIO_ONLY_JSON);
    final Format noAudio = format(VIDEO_ONLY_JSON);
    final Format unknown = format(EMPTY_JSON);
    final boolean audioOnlyHasAudio = FormatStrategy.hasAudio(audioOnly);
    final boolean noAudioHasAudio = FormatStrategy.hasAudio(noAudio);
    final boolean unknownHasAudio = FormatStrategy.hasAudio(unknown);
    assertTrue(audioOnlyHasAudio);
    assertFalse(noAudioHasAudio);
    assertFalse(unknownHasAudio);
    assertThrows(NullPointerException.class, () -> FormatStrategy.hasAudio(null));
  }

  @Test
  void shouldDetectVideoStreams() {
    final Format video = format(VIDEO_ONLY_JSON);
    final Format noVideo = format(AUDIO_ONLY_JSON);
    final Format unknown = format(EMPTY_JSON);
    final boolean videoHasVideo = FormatStrategy.hasVideo(video);
    final boolean noVideoHasVideo = FormatStrategy.hasVideo(noVideo);
    final boolean unknownHasVideo = FormatStrategy.hasVideo(unknown);
    assertTrue(videoHasVideo);
    assertFalse(noVideoHasVideo);
    assertFalse(unknownHasVideo);
    assertThrows(NullPointerException.class, () -> FormatStrategy.hasVideo(null));
  }

  @Test
  void shouldDetectPlainHttpsStreams() {
    final Format https = format("{\"protocol\": \"https\"}");
    final Format http = format("{\"protocol\": \"http\"}");
    final Format hls = format("{\"protocol\": \"m3u8_native\"}");
    final Format unknown = format(EMPTY_JSON);
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
