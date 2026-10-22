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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MediaInfo}.
 */
final class MediaInfoTest {

  static UrlParseDump completeDump() {
    final String json = """
    {
      "title": "Song",
      "uploader": "Artist",
      "channel": "Artist Channel",
      "uploader_id": "@artist",
      "description": "A description",
      "thumbnail": "https://example.com/thumbnail.jpg",
      "duration": 215.2,
      "view_count": 3000000000,
      "like_count": 50,
      "upload_date": "20240101",
      "webpage_url": "https://example.com/watch"
    }
    """;
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  @Test
  void shouldTakeTheShownFieldsFromYtDlpInYtDlpOrderAndNames() {
    final UrlParseDump dump = completeDump();
    final MediaInfo info = MediaInfo.of(dump);
    final Map<String, Object> json = info.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("title", "Song");
    expected.put("uploader", "Artist");
    expected.put("channel", "Artist Channel");
    expected.put("uploader_id", "@artist");
    expected.put("description", "A description");
    expected.put("thumbnail", "https://example.com/thumbnail.jpg");
    expected.put("duration", 216L);
    expected.put("view_count", 3_000_000_000L);
    expected.put("like_count", 50L);
    expected.put("upload_date", "20240101");
    assertEquals(expected, json);
    final Set<String> keys = json.keySet();
    final List<String> order = new ArrayList<>(keys);
    final Set<String> expectedKeys = expected.keySet();
    final List<String> expectedOrder = new ArrayList<>(expectedKeys);
    assertEquals(expectedOrder, order);
    final String title = info.getTitle();
    assertEquals("Song", title);
  }

  @Test
  void shouldLeaveOutUnknownTextFieldsButKeepTheNumbers() {
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump dump = gson.fromJson("{\"uploader\": \"Artist\"}", UrlParseDump.class);
    final MediaInfo info = MediaInfo.of(dump);
    final Map<String, Object> json = info.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("uploader", "Artist");
    expected.put("duration", 0L);
    expected.put("view_count", 0L);
    expected.put("like_count", 0L);
    assertEquals(expected, json);
    final String title = info.getTitle();
    assertNull(title);
  }

  @Test
  void shouldGiveEmptyInformationOnlyZeroStatistics() {
    final Map<String, Object> json = MediaInfo.EMPTY.toJson();
    final Map<String, Object> expected = Map.of("duration", 0L, "view_count", 0L, "like_count", 0L);
    assertEquals(expected, json);
    final String title = MediaInfo.EMPTY.getTitle();
    assertNull(title);
  }

  @Test
  void shouldGiveTitledInformationOnlyATitle() {
    final MediaInfo info = MediaInfo.titled("clip.mp4");
    final Map<String, Object> json = info.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("title", "clip.mp4");
    expected.put("duration", 0L);
    expected.put("view_count", 0L);
    expected.put("like_count", 0L);
    assertEquals(expected, json);
    final String title = info.getTitle();
    assertEquals("clip.mp4", title);
  }

  @Test
  void shouldReturnANewMapEveryTime() {
    final MediaInfo info = MediaInfo.titled("clip.mp4");
    final Map<String, Object> first = info.toJson();
    first.put("title", "changed");
    final Map<String, Object> second = info.toJson();
    final Object title = second.get("title");
    assertEquals("clip.mp4", title);
  }

  @Test
  void shouldRejectNulls() {
    assertThrows(NullPointerException.class, () -> MediaInfo.of(null));
    assertThrows(NullPointerException.class, () -> MediaInfo.titled(null));
  }
}
