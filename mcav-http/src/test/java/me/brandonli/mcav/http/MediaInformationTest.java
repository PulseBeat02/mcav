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
import me.brandonli.mcav.http.testing.UrlParseDumpUtils;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MediaInformation}.
 */
final class MediaInformationTest {

  private static final String TITLE_KEY = "title";
  private static final String UPLOADER_KEY = "uploader";
  private static final String DURATION_KEY = "duration";
  private static final String VIEW_COUNT_KEY = "view_count";
  private static final String LIKE_COUNT_KEY = "like_count";

  private static final String FILE_NAME = "clip.mp4";

  @Test
  void shouldTakeTheShownFieldsFromYtDlpInYtDlpOrderAndNames() {
    final UrlParseDump dump = UrlParseDumpUtils.completeDump();
    final MediaInformation information = MediaInformation.of(dump);
    final Map<String, Object> json = information.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put(TITLE_KEY, "Song");
    expected.put(UPLOADER_KEY, "Artist");
    expected.put("channel", "Artist Channel");
    expected.put("uploader_id", "@artist");
    expected.put("description", "A description");
    expected.put("thumbnail", "https://example.com/thumbnail.jpg");
    expected.put(DURATION_KEY, 216L);
    expected.put(VIEW_COUNT_KEY, 3_000_000_000L);
    expected.put(LIKE_COUNT_KEY, 50L);
    expected.put("upload_date", "20240101");
    assertEquals(expected, json);

    final Set<String> keys = json.keySet();
    final List<String> order = new ArrayList<>(keys);
    final Set<String> expectedKeys = expected.keySet();
    final List<String> expectedOrder = new ArrayList<>(expectedKeys);
    assertEquals(expectedOrder, order);

    final String title = information.getTitle();
    assertEquals("Song", title);
  }

  @Test
  void shouldLeaveOutUnknownTextFieldsButKeepTheNumbers() {
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump dump = gson.fromJson("{\"uploader\": \"Artist\"}", UrlParseDump.class);
    final MediaInformation information = MediaInformation.of(dump);
    final Map<String, Object> json = information.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put(UPLOADER_KEY, "Artist");
    expected.put(DURATION_KEY, 0L);
    expected.put(VIEW_COUNT_KEY, 0L);
    expected.put(LIKE_COUNT_KEY, 0L);
    assertEquals(expected, json);

    final String title = information.getTitle();
    assertNull(title);
  }

  @Test
  void shouldGiveEmptyInformationOnlyZeroStatistics() {
    final Map<String, Object> json = MediaInformation.EMPTY.toJson();
    final Map<String, Object> expected = Map.of(DURATION_KEY, 0L, VIEW_COUNT_KEY, 0L, LIKE_COUNT_KEY, 0L);
    assertEquals(expected, json);

    final String title = MediaInformation.EMPTY.getTitle();
    assertNull(title);
  }

  @Test
  void shouldGiveTitledInformationOnlyATitle() {
    final MediaInformation information = MediaInformation.titled(FILE_NAME);
    final Map<String, Object> json = information.toJson();
    final Map<String, Object> expected = new LinkedHashMap<>();
    expected.put(TITLE_KEY, FILE_NAME);
    expected.put(DURATION_KEY, 0L);
    expected.put(VIEW_COUNT_KEY, 0L);
    expected.put(LIKE_COUNT_KEY, 0L);
    assertEquals(expected, json);

    final String title = information.getTitle();
    assertEquals(FILE_NAME, title);
  }

  @Test
  void shouldReturnANewMapEveryTime() {
    final MediaInformation information = MediaInformation.titled(FILE_NAME);
    final Map<String, Object> first = information.toJson();
    first.put(TITLE_KEY, "changed");

    final Map<String, Object> second = information.toJson();
    final Object title = second.get(TITLE_KEY);
    assertEquals(FILE_NAME, title);
  }

  @Test
  void shouldRejectNulls() {
    assertThrows(NullPointerException.class, () -> MediaInformation.of(null));
    assertThrows(NullPointerException.class, () -> MediaInformation.titled(null));
  }
}
