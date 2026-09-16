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

import com.google.common.base.Preconditions;
import java.util.LinkedHashMap;
import java.util.Map;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The information about the current media that the web player shows: title, artist, thumbnail, and a few
 * statistics. Only these fields are sent to browsers, not the complete yt-dlp output.
 */
public final class MediaInfo {

  /**
   * Information for when nothing is playing.
   */
  public static final MediaInfo EMPTY = new MediaInfo(null, null, null, null, null, null, 0, 0, 0, null);

  private final @Nullable String title;
  private final @Nullable String uploader;
  private final @Nullable String channel;
  private final @Nullable String uploaderId;
  private final @Nullable String description;
  private final @Nullable String thumbnail;
  private final long duration;
  private final long viewCount;
  private final long likeCount;
  private final @Nullable String uploadDate;

  MediaInfo(
    final @Nullable String title,
    final @Nullable String uploader,
    final @Nullable String channel,
    final @Nullable String uploaderId,
    final @Nullable String description,
    final @Nullable String thumbnail,
    final long duration,
    final long viewCount,
    final long likeCount,
    final @Nullable String uploadDate
  ) {
    this.title = title;
    this.uploader = uploader;
    this.channel = channel;
    this.uploaderId = uploaderId;
    this.description = description;
    this.thumbnail = thumbnail;
    this.duration = duration;
    this.viewCount = viewCount;
    this.likeCount = likeCount;
    this.uploadDate = uploadDate;
  }

  /**
   * Takes the shown fields from the output of yt-dlp. The duration is shown in whole seconds, rounded up, so a
   * video of 215.2 seconds is shown as 216 seconds.
   *
   * @param dump the output of yt-dlp
   * @return the information
   */
  public static MediaInfo of(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    final double wholeSeconds = Math.ceil(dump.duration);
    final long duration = (long) wholeSeconds;
    return new MediaInfo(
      dump.title,
      dump.uploader,
      dump.channel,
      dump.uploader_id,
      dump.description,
      dump.thumbnail,
      duration,
      dump.view_count,
      dump.like_count,
      dump.upload_date
    );
  }

  /**
   * Creates information with only a title, for media that did not come from yt-dlp.
   *
   * @param title the title
   * @return the information
   */
  public static MediaInfo titled(final String title) {
    Preconditions.checkNotNull(title, "Title must not be null");
    return new MediaInfo(title, null, null, null, null, null, 0, 0, 0, null);
  }

  /**
   * Gets the title.
   *
   * @return the title, or null if unknown
   */
  public @Nullable String getTitle() {
    return this.title;
  }

  /**
   * Converts the information into the JSON object the web player reads, with the field names of yt-dlp.
   *
   * @return the fields; unknown text fields are left out
   */
  Map<String, Object> toJson() {
    final Map<String, Object> json = new LinkedHashMap<>();
    putIfPresent(json, "title", this.title);
    putIfPresent(json, "uploader", this.uploader);
    putIfPresent(json, "channel", this.channel);
    putIfPresent(json, "uploader_id", this.uploaderId);
    putIfPresent(json, "description", this.description);
    putIfPresent(json, "thumbnail", this.thumbnail);
    json.put("duration", this.duration);
    json.put("view_count", this.viewCount);
    json.put("like_count", this.likeCount);
    putIfPresent(json, "upload_date", this.uploadDate);
    return json;
  }

  private static void putIfPresent(final Map<String, Object> json, final String key, final @Nullable String value) {
    if (value != null) {
      json.put(key, value);
    }
  }
}
