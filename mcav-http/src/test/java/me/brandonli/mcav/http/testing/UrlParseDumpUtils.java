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
package me.brandonli.mcav.http.testing;

import com.google.gson.Gson;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;

public final class UrlParseDumpUtils {

  private UrlParseDumpUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static UrlParseDump completeDump() {
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
}
