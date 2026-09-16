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
package me.brandonli.mcav.json.ytdlp.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.junit.jupiter.api.Test;

/**
 * Tests the yt-dlp JSON model: {@link URLParseDump}, {@link Format}, {@link Fragment}, {@link Heatmap},
 * {@link HttpHeaders}, {@link Thumbnail}, {@link Version} and {@link DownloaderOptions}.
 */
final class FormatModelTest {

  private static final double DELTA = 1.0e-9;

  private static URLParseDump fixture() throws IOException {
    try (final InputStream stream = FormatModelTest.class.getResourceAsStream("/ytdlp/video.json")) {
      assertNotNull(stream, "the fixture must be on the test classpath");
      final byte[] bytes = stream.readAllBytes();
      final String json = new String(bytes, StandardCharsets.UTF_8);
      final Gson gson = GsonProvider.getSimple();
      return gson.fromJson(json, URLParseDump.class);
    }
  }

  private static Format format(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, Format.class);
  }

  @Test
  void readsTheVideoMetadata() throws IOException {
    final URLParseDump dump = fixture();
    final List<String> categories = dump.categories;
    final List<String> tags = dump.tags;
    final List<String> sortFields = dump._format_sort_fields;
    assertEquals("dQw4w9WgXcQ", dump.id);
    assertEquals("Example Video", dump.title);
    assertEquals("Example Video", dump.fulltitle);
    assertEquals("An example description\nwith two lines", dump.description);
    assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", dump.thumbnail);
    assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", dump.channel_id);
    assertEquals("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", dump.channel_url);
    assertEquals("Example Channel", dump.channel);
    assertTrue(dump.channel_is_verified);
    assertEquals("Example Uploader", dump.uploader);
    assertEquals("@example", dump.uploader_id);
    assertEquals("https://www.youtube.com/@example", dump.uploader_url);
    assertEquals("20091025", dump.upload_date);
    assertEquals(1256453732, dump.timestamp);
    assertEquals(212, dump.duration);
    assertEquals("3:32", dump.duration_string);
    assertEquals(1700000000, dump.view_count);
    assertEquals(18000000, dump.like_count);
    assertEquals(0, dump.age_limit);
    assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", dump.webpage_url);
    assertEquals("https://youtu.be/dQw4w9WgXcQ", dump.original_url);
    assertEquals("watch", dump.webpage_url_basename);
    assertEquals("youtube.com", dump.webpage_url_domain);
    assertEquals("youtube", dump.extractor);
    assertEquals("Youtube", dump.extractor_key);
    assertEquals("dQw4w9WgXcQ", dump.display_id);
    assertEquals("public", dump.availability);
    assertEquals("not_live", dump.live_status);
    assertTrue(dump.playable_in_embed);
    assertFalse(dump.is_live);
    assertFalse(dump.was_live);
    final List<String> expectedCategories = List.of("Music");
    final List<String> expectedTags = List.of("example", "video");
    final List<String> expectedSortFields = List.of("quality", "res", "fps");
    assertEquals(expectedCategories, categories);
    assertEquals(expectedTags, tags);
    assertEquals(expectedSortFields, sortFields);
    assertEquals(1757808000, dump.epoch);
    assertEquals("video", dump._type);
    assertEquals("Example Video [dQw4w9WgXcQ].mp4", dump._filename);
    assertEquals("Example Video [dQw4w9WgXcQ].mp4", dump.filename);
  }

  @Test
  void readsTheRequestedFormatAtTheTopLevel() throws IOException {
    final URLParseDump dump = fixture();
    final DownloaderOptions options = dump.downloader_options;
    final HttpHeaders headers = dump.http_headers;
    assertEquals("137+251", dump.format_id);
    assertEquals("1080p+medium", dump.format_note);
    assertEquals("mp4", dump.ext);
    assertEquals("avc1.640028", dump.vcodec);
    assertEquals("opus", dump.acodec);
    assertEquals("https+https", dump.protocol);
    assertEquals("1920x1080", dump.resolution);
    assertEquals(1920, dump.width);
    assertEquals(1080, dump.height);
    assertEquals(29.97, dump.fps, DELTA);
    assertEquals(9.0, dump.quality, DELTA);
    assertEquals(4530.189, dump.tbr, DELTA);
    assertEquals(1.78, dump.aspect_ratio, DELTA);
    assertEquals(48000, dump.asr);
    assertEquals(2, dump.audio_channels);
    assertEquals(-1, dump.source_preference);
    assertEquals(-1, dump.language_preference);
    assertEquals("en", dump.language);
    assertEquals("SDR", dump.dynamic_range);
    assertEquals("mp4", dump.video_ext);
    assertEquals("none", dump.audio_ext);
    assertEquals("137 - 1920x1080 (1080p)+251 - audio only (medium)", dump.format);
    assertFalse(dump.has_drm);
    assertNull(dump.url);
    assertNotNull(options);
    assertEquals(10485760, options.http_chunk_size);
    assertNotNull(headers);
    assertEquals("Mozilla/5.0 (X11; Linux x86_64)", headers.userAgent);
  }

  @Test
  void readsCountsAndSizesBeyondTheIntRange() throws IOException {
    final URLParseDump dump = fixture();
    final List<Format> formats = dump.formats;
    assertNotNull(formats);
    final Format video = formats.get(2);
    assertEquals(2300000000L, dump.comment_count);
    assertEquals(4100000000L, dump.channel_follower_count);
    assertEquals(5403437753L, dump.filesize_approx);
    assertEquals(5368709120L, video.filesize);
    assertEquals(5400000000L, video.filesize_approx);
  }

  @Test
  void leavesUnknownValuesNullOrZero() throws IOException {
    final URLParseDump dump = fixture();
    final List<Format> formats = dump.formats;
    assertNotNull(formats);
    final Format audio = formats.get(1);
    assertNull(dump.average_rating);
    assertNull(dump.release_timestamp);
    assertNull(dump.chapters);
    assertNull(dump.playlist);
    assertNull(dump.playlist_index);
    assertNull(dump.release_year);
    assertNull(dump.requested_subtitles);
    assertNull(dump._has_drm);
    assertNull(dump.filesize);
    assertNull(dump.preference);
    assertNull(dump.vbr);
    assertNull(dump.abr);
    assertEquals(0, audio.width);
    assertEquals(0, audio.height);
    assertEquals(0.0, audio.fps, DELTA);
    assertEquals(0.0, audio.aspect_ratio, DELTA);
    assertNull(audio.preference);
    assertNull(audio.dynamic_range);
    assertNull(audio.fragments);
    final URLParseDump empty = new URLParseDump();
    assertNull(empty.id);
    assertNull(empty.formats);
    assertEquals(0, empty.duration);
  }

  @Test
  void readsEveryFormatField() throws IOException {
    final URLParseDump dump = fixture();
    final List<Format> formats = dump.formats;
    assertNotNull(formats);
    final Format audio = formats.get(1);
    final Format video = formats.get(2);
    final DownloaderOptions options = audio.downloader_options;
    assertEquals("251", audio.format_id);
    assertEquals("medium", audio.format_note);
    assertEquals("webm", audio.ext);
    assertEquals("https", audio.protocol);
    assertEquals("opus", audio.acodec);
    assertEquals("none", audio.vcodec);
    assertEquals("https://rr1---sn.example.googlevideo.com/videoplayback?itag=251", audio.url);
    assertEquals(2, audio.audio_channels);
    assertEquals(3.0, audio.quality, DELTA);
    assertFalse(audio.has_drm);
    assertEquals(-1, audio.source_preference);
    assertEquals(48000, audio.asr);
    assertEquals(3437753L, audio.filesize);
    assertEquals("en", audio.language);
    assertEquals(-1, audio.language_preference);
    assertEquals("webm_dash", audio.container);
    assertEquals("webm", audio.audio_ext);
    assertEquals("none", audio.video_ext);
    assertEquals(0.0, audio.vbr, DELTA);
    assertEquals(129.689, audio.abr, DELTA);
    assertEquals(129.689, audio.tbr, DELTA);
    assertEquals("audio only", audio.resolution);
    assertEquals("251 - audio only (medium)", audio.format);
    assertNotNull(options);
    assertEquals(10485760, options.http_chunk_size);
    assertEquals(1920, video.width);
    assertEquals(1080, video.height);
    assertEquals(29.97, video.fps, DELTA);
    assertEquals(4400.5, video.vbr, DELTA);
    assertEquals("SDR", video.dynamic_range);
    assertEquals("mp4_dash", video.container);
    assertNull(video.format_index);
    assertNull(video.manifest_url);
  }

  @Test
  void readsStoryboardFragments() throws IOException {
    final URLParseDump dump = fixture();
    final List<Format> formats = dump.formats;
    assertNotNull(formats);
    final Format storyboard = formats.getFirst();
    final List<Fragment> fragments = storyboard.fragments;
    assertNotNull(fragments);
    final Fragment fragment = fragments.getFirst();
    assertEquals("mhtml", storyboard.protocol);
    assertEquals(10, storyboard.rows);
    assertEquals(10, storyboard.columns);
    assertEquals(0.5, storyboard.fps, DELTA);
    assertEquals(0L, storyboard.filesize_approx);
    final int fragmentsCount = fragments.size();
    assertEquals(1, fragmentsCount);
    assertEquals("https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L0/default.jpg?sqp=a", fragment.url);
    assertEquals(212.0, fragment.duration, DELTA);
  }

  @Test
  void mapsTheHttpHeaderNames() throws IOException {
    final URLParseDump dump = fixture();
    final List<Format> formats = dump.formats;
    assertNotNull(formats);
    final Format storyboard = formats.getFirst();
    final HttpHeaders headers = storyboard.http_headers;
    assertNotNull(headers);
    assertEquals("Mozilla/5.0 (X11; Linux x86_64)", headers.userAgent);
    assertEquals("text/html,application/xhtml+xml", headers.accept);
    assertEquals("en-us,en;q=0.5", headers.acceptLanguage);
    assertEquals("navigate", headers.secFetchMode);
    final Gson gson = GsonProvider.getSimple();
    final String json = gson.toJson(headers);
    assertEquals(
      "{\"User-Agent\":\"Mozilla/5.0 (X11; Linux x86_64)\",\"Accept\":\"text/html,application/xhtml+xml\",\"Accept-Language\":\"en-us,en;q\\u003d0.5\",\"Sec-Fetch-Mode\":\"navigate\"}",
      json
    );
  }

  @Test
  void readsThumbnailsHeatmapAndVersion() throws IOException {
    final URLParseDump dump = fixture();
    final List<Thumbnail> thumbnails = dump.thumbnails;
    final List<Heatmap> heatmap = dump.heatmap;
    final Version version = dump._version;
    assertNotNull(thumbnails);
    assertNotNull(heatmap);
    assertNotNull(version);
    final Thumbnail thumbnail = thumbnails.getFirst();
    final Heatmap marker = heatmap.getFirst();
    assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", thumbnail.url);
    assertEquals(-1, thumbnail.preference);
    assertEquals("40", thumbnail.id);
    assertEquals(1080, thumbnail.height);
    assertEquals(1920, thumbnail.width);
    assertEquals("1920x1080", thumbnail.resolution);
    assertEquals(0.0, marker.start_time, DELTA);
    assertEquals(2.12, marker.end_time, DELTA);
    assertEquals(1.0, marker.value, DELTA);
    assertEquals("2025.09.05", version.version);
    assertNull(version.current_git_head);
    assertEquals("abc123def456", version.release_git_head);
    assertEquals("yt-dlp/yt-dlp", version.repository);
  }

  @Test
  void convertsTheStreamUrlIntoASource() {
    final Format format = format("{\"url\": \"https://cdn.example.com/video.mp4?token=a%20b\"}");
    final UriSource source = format.toUriSource();
    final URI uri = source.getUri();
    final URI expected = URI.create("https://cdn.example.com/video.mp4?token=a%20b");
    assertEquals(expected, uri);
  }

  @Test
  void rejectsStreamsWithoutUrl() {
    final Format format = format("{\"format_id\": \"sb0\"}");
    final IllegalStateException exception = assertThrows(IllegalStateException.class, format::toUriSource);
    final String message = exception.getMessage();
    assertEquals("Stream has no URL", message);
  }
}
