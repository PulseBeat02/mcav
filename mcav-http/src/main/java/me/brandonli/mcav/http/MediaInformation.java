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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The information about the current media that the web player shows: title, artist, thumbnail, and a few
 * statistics. Instances are immutable snapshots and can be shared across threads. Only these fields are sent
 * to browsers, not the complete yt-dlp output.
 *
 * @since 1.0.0
 */
public final class MediaInformation {

  /**
   * Information for when nothing is playing.
   *
   * @since 1.0.0
   */
  public static final MediaInformation EMPTY = new MediaInformation(null, null, null, null, null, null, 0, 0, 0, null);

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

  MediaInformation(
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
   * @param dump the non-null output of yt-dlp; do not mutate it while this snapshot is taken
   * @return a new immutable snapshot, with missing text fields retained as null
   * @throws NullPointerException if {@code dump} is null
   * @since 1.0.0
   */
  public static MediaInformation of(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null!");

    final double reportedDuration = dump.getDuration();
    final double wholeSeconds = Math.ceil(reportedDuration);
    final long duration = (long) wholeSeconds;
    final Optional<String> title = dump.getTitle();
    final Optional<String> uploader = dump.getUploader();
    final Optional<String> channel = dump.getChannel();
    final Optional<String> uploaderId = dump.getUploaderId();
    final Optional<String> description = dump.getDescription();
    final Optional<String> thumbnail = dump.getThumbnail();
    final Optional<String> uploadDate = dump.getUploadDate();
    final long viewCount = dump.getViewCount();
    final long likeCount = dump.getLikeCount();
    return new MediaInformation(
      title.orElse(null),
      uploader.orElse(null),
      channel.orElse(null),
      uploaderId.orElse(null),
      description.orElse(null),
      thumbnail.orElse(null),
      duration,
      viewCount,
      likeCount,
      uploadDate.orElse(null)
    );
  }

  /**
   * Creates information with only a title, for media that did not come from yt-dlp.
   *
   * @param title the non-null title, retained verbatim; empty or blank strings are allowed
   * @return a new immutable snapshot with that title, unknown other text fields and zero statistics
   * @throws NullPointerException if {@code title} is null
   * @since 1.0.0
   */
  public static MediaInformation titled(final String title) {
    Preconditions.checkNotNull(title, "Title must not be null!");

    return new MediaInformation(title, null, null, null, null, null, 0, 0, 0, null);
  }

  Map<String, Object> toJson() {
    final Map<String, Object> json = new LinkedHashMap<>();
    this.putIfPresent(json, "title", this.title);
    this.putIfPresent(json, "uploader", this.uploader);
    this.putIfPresent(json, "channel", this.channel);
    this.putIfPresent(json, "uploader_id", this.uploaderId);
    this.putIfPresent(json, "description", this.description);
    this.putIfPresent(json, "thumbnail", this.thumbnail);
    json.put("duration", this.duration);
    json.put("view_count", this.viewCount);
    json.put("like_count", this.likeCount);
    this.putIfPresent(json, "upload_date", this.uploadDate);
    return json;
  }

  private void putIfPresent(final Map<String, Object> json, final String key, final @Nullable String value) {
    if (value != null) {
      json.put(key, value);
    }
  }

  /**
   * Returns the title.
   *
   * @return the title, or empty if unknown
   * @since 1.0.0
   */
  public Optional<String> getTitle() {
    return Optional.ofNullable(this.title);
  }

  /**
   * Returns whether {@code other} is media information with the same values.
   *
   * @param other the object to compare with
   * @return whether every value matches
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final MediaInformation information)) {
      return false;
    }

    return (
      Objects.equals(this.title, information.title) &&
      Objects.equals(this.uploader, information.uploader) &&
      Objects.equals(this.channel, information.channel) &&
      Objects.equals(this.uploaderId, information.uploaderId) &&
      Objects.equals(this.description, information.description) &&
      Objects.equals(this.thumbnail, information.thumbnail) &&
      this.duration == information.duration &&
      this.viewCount == information.viewCount &&
      this.likeCount == information.likeCount &&
      Objects.equals(this.uploadDate, information.uploadDate)
    );
  }

  /**
   * Returns a hash code built from every value.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(
      this.title,
      this.uploader,
      this.channel,
      this.uploaderId,
      this.description,
      this.thumbnail,
      this.duration,
      this.viewCount,
      this.likeCount,
      this.uploadDate
    );
  }

  /**
   * Returns a debugging representation with every value.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("title", this.title)
      .add("uploader", this.uploader)
      .add("channel", this.channel)
      .add("uploaderId", this.uploaderId)
      .add("description", this.description)
      .add("thumbnail", this.thumbnail)
      .add("duration", this.duration)
      .add("viewCount", this.viewCount)
      .add("likeCount", this.likeCount)
      .add("uploadDate", this.uploadDate)
      .toString();
  }
}
