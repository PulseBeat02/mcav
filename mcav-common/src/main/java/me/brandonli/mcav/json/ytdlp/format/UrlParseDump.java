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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The metadata yt-dlp prints for a video with {@code --dump-json}: the details of the video, every available stream
 * in {@link #getFormats()}, and the values of the stream yt-dlp would download by default, which is often a merge of
 * the best video and the best audio stream.
 *
 * <p>Gson fills instances from the JSON yt-dlp prints. Values yt-dlp did not report are empty, zero or false; a
 * primitive zero or false does not tell a missing value from a reported one.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
@JsonAdapter(UrlParseDump.Deserializer.class)
public final class UrlParseDump {

  private static final String TITLE_NULL_MESSAGE = "Title must not be null!";

  private static final double NANOSECONDS_PER_SECOND = 1_000_000_000.0;

  private final @Nullable String id;

  private final @Nullable String title;

  private final @Nullable List<Format> formats;

  private final @Nullable List<Thumbnail> thumbnails;

  private final @Nullable String thumbnail;

  private final @Nullable String description;

  @SerializedName("channel_id")
  private final @Nullable String channelId;

  @SerializedName("channel_url")
  private final @Nullable String channelUrl;

  private final double duration;

  @SerializedName("view_count")
  private final long viewCount;

  @SerializedName("average_rating")
  private final @Nullable Object averageRating;

  @SerializedName("age_limit")
  private final int ageLimit;

  @SerializedName("webpage_url")
  private final @Nullable String webpageUrl;

  private final @Nullable List<String> categories;

  private final @Nullable List<String> tags;

  @SerializedName("playable_in_embed")
  private final boolean playableInEmbed;

  @SerializedName("live_status")
  private final @Nullable String liveStatus;

  @SerializedName("release_timestamp")
  private final @Nullable Object releaseTimestamp;

  @SerializedName("_format_sort_fields")
  private final @Nullable List<String> formatSortFields;

  @SerializedName("comment_count")
  private final long commentCount;

  private final @Nullable Object chapters;

  private final @Nullable List<Heatmap> heatmap;

  @SerializedName("like_count")
  private final long likeCount;

  private final @Nullable String channel;

  @SerializedName("channel_follower_count")
  private final long channelFollowerCount;

  @SerializedName("channel_is_verified")
  private final boolean channelVerified;

  private final @Nullable String uploader;

  @SerializedName("uploader_id")
  private final @Nullable String uploaderId;

  @SerializedName("uploader_url")
  private final @Nullable String uploaderUrl;

  @SerializedName("upload_date")
  private final @Nullable String uploadDate;

  private final int timestamp;

  private final @Nullable String availability;

  @SerializedName("original_url")
  private final @Nullable String originalUrl;

  @SerializedName("webpage_url_basename")
  private final @Nullable String webpageUrlBasename;

  @SerializedName("webpage_url_domain")
  private final @Nullable String webpageUrlDomain;

  private final @Nullable String extractor;

  @SerializedName("extractor_key")
  private final @Nullable String extractorKey;

  private final @Nullable Object playlist;

  @SerializedName("playlist_index")
  private final @Nullable Object playlistIndex;

  @SerializedName("display_id")
  private final @Nullable String displayId;

  @SerializedName("fulltitle")
  private final @Nullable String fullTitle;

  @SerializedName("duration_string")
  private final @Nullable String durationString;

  @SerializedName("release_year")
  private final @Nullable Object releaseYear;

  @SerializedName("is_live")
  private final boolean live;

  @SerializedName("was_live")
  private final boolean wasLive;

  @SerializedName("requested_subtitles")
  private final @Nullable Object requestedSubtitles;

  @SerializedName("_has_drm")
  private final @Nullable Object anyStreamDrmProtected;

  private final int epoch;

  @SerializedName("asr")
  private final int audioSampleRate;

  @SerializedName("filesize")
  private final @Nullable Object fileSize;

  @SerializedName("format_id")
  private final @Nullable String formatId;

  @SerializedName("format_note")
  private final @Nullable String formatNote;

  @SerializedName("source_preference")
  private final int sourcePreference;

  @SerializedName("fps")
  private final double framesPerSecond;

  @SerializedName("audio_channels")
  private final int audioChannels;

  private final int height;

  private final double quality;

  @SerializedName("has_drm")
  private final boolean drmProtected;

  @SerializedName("tbr")
  private final double totalBitrate;

  @SerializedName("filesize_approx")
  private final long approximateFileSize;

  private final @Nullable String url;

  private final int width;

  private final @Nullable String language;

  @SerializedName("language_preference")
  private final int languagePreference;

  private final @Nullable Object preference;

  @SerializedName("ext")
  private final @Nullable String extension;

  @SerializedName("vcodec")
  private final @Nullable String videoCodec;

  @SerializedName("acodec")
  private final @Nullable String audioCodec;

  @SerializedName("dynamic_range")
  private final @Nullable String dynamicRange;

  @SerializedName("downloader_options")
  private final @Nullable DownloaderOptions downloaderOptions;

  private final @Nullable String protocol;

  private final @Nullable String resolution;

  @SerializedName("aspect_ratio")
  private final double aspectRatio;

  @SerializedName("http_headers")
  private final @Nullable HttpHeaders httpHeaders;

  @SerializedName("video_ext")
  private final @Nullable String videoExtension;

  @SerializedName("audio_ext")
  private final @Nullable String audioExtension;

  @SerializedName("vbr")
  private final @Nullable Object videoBitrate;

  @SerializedName("abr")
  private final @Nullable Object audioBitrate;

  private final @Nullable String format;

  @SerializedName("_filename")
  private final @Nullable String fileName;

  @SerializedName("filename")
  private final @Nullable String fileNameAlias;

  @SerializedName("_type")
  private final @Nullable String type;

  @SerializedName("_version")
  private final @Nullable Version version;

  private UrlParseDump(final Fields fields) {
    this.id = fields.id();
    this.title = fields.title();
    this.formats = fields.formats();
    this.thumbnails = fields.thumbnails();
    this.thumbnail = fields.thumbnail();
    this.description = fields.description();
    this.channelId = fields.channelId();
    this.channelUrl = fields.channelUrl();
    this.duration = Objects.requireNonNullElse(fields.duration(), 0.0);
    this.viewCount = Objects.requireNonNullElse(fields.viewCount(), 0L);
    this.averageRating = fields.averageRating();
    this.ageLimit = Objects.requireNonNullElse(fields.ageLimit(), 0);
    this.webpageUrl = fields.webpageUrl();
    this.categories = fields.categories();
    this.tags = fields.tags();
    this.playableInEmbed = Objects.requireNonNullElse(fields.playableInEmbed(), false);
    this.liveStatus = fields.liveStatus();
    this.releaseTimestamp = fields.releaseTimestamp();
    this.formatSortFields = fields.formatSortFields();
    this.commentCount = Objects.requireNonNullElse(fields.commentCount(), 0L);
    this.chapters = fields.chapters();
    this.heatmap = fields.heatmap();
    this.likeCount = Objects.requireNonNullElse(fields.likeCount(), 0L);
    this.channel = fields.channel();
    this.channelFollowerCount = Objects.requireNonNullElse(fields.channelFollowerCount(), 0L);
    this.channelVerified = Objects.requireNonNullElse(fields.channelVerified(), false);
    this.uploader = fields.uploader();
    this.uploaderId = fields.uploaderId();
    this.uploaderUrl = fields.uploaderUrl();
    this.uploadDate = fields.uploadDate();
    this.timestamp = Objects.requireNonNullElse(fields.timestamp(), 0);
    this.availability = fields.availability();
    this.originalUrl = fields.originalUrl();
    this.webpageUrlBasename = fields.webpageUrlBasename();
    this.webpageUrlDomain = fields.webpageUrlDomain();
    this.extractor = fields.extractor();
    this.extractorKey = fields.extractorKey();
    this.playlist = fields.playlist();
    this.playlistIndex = fields.playlistIndex();
    this.displayId = fields.displayId();
    this.fullTitle = fields.fullTitle();
    this.durationString = fields.durationString();
    this.releaseYear = fields.releaseYear();
    this.live = Objects.requireNonNullElse(fields.live(), false);
    this.wasLive = Objects.requireNonNullElse(fields.wasLive(), false);
    this.requestedSubtitles = fields.requestedSubtitles();
    this.anyStreamDrmProtected = fields.anyStreamDrmProtected();
    this.epoch = Objects.requireNonNullElse(fields.epoch(), 0);
    this.audioSampleRate = Objects.requireNonNullElse(fields.audioSampleRate(), 0);
    this.fileSize = fields.fileSize();
    this.formatId = fields.formatId();
    this.formatNote = fields.formatNote();
    this.sourcePreference = Objects.requireNonNullElse(fields.sourcePreference(), 0);
    this.framesPerSecond = Objects.requireNonNullElse(fields.framesPerSecond(), 0.0);
    this.audioChannels = Objects.requireNonNullElse(fields.audioChannels(), 0);
    this.height = Objects.requireNonNullElse(fields.height(), 0);
    this.quality = Objects.requireNonNullElse(fields.quality(), 0.0);
    this.drmProtected = Objects.requireNonNullElse(fields.drmProtected(), false);
    this.totalBitrate = Objects.requireNonNullElse(fields.totalBitrate(), 0.0);
    this.approximateFileSize = Objects.requireNonNullElse(fields.approximateFileSize(), 0L);
    this.url = fields.url();
    this.width = Objects.requireNonNullElse(fields.width(), 0);
    this.language = fields.language();
    this.languagePreference = Objects.requireNonNullElse(fields.languagePreference(), 0);
    this.preference = fields.preference();
    this.extension = fields.extension();
    this.videoCodec = fields.videoCodec();
    this.audioCodec = fields.audioCodec();
    this.dynamicRange = fields.dynamicRange();
    this.downloaderOptions = fields.downloaderOptions();
    this.protocol = fields.protocol();
    this.resolution = fields.resolution();
    this.aspectRatio = Objects.requireNonNullElse(fields.aspectRatio(), 0.0);
    this.httpHeaders = fields.httpHeaders();
    this.videoExtension = fields.videoExtension();
    this.audioExtension = fields.audioExtension();
    this.videoBitrate = fields.videoBitrate();
    this.audioBitrate = fields.audioBitrate();
    this.format = fields.format();
    this.fileName = fields.fileName();
    this.fileNameAlias = fields.fileNameAlias();
    this.type = fields.type();
    this.version = fields.version();
  }

  private UrlParseDump(final @Nullable String title, final @Nullable String description, final double duration) {
    this.id = null;
    this.title = title;
    this.formats = null;
    this.thumbnails = null;
    this.thumbnail = null;
    this.description = description;
    this.channelId = null;
    this.channelUrl = null;
    this.duration = duration;
    this.viewCount = 0L;
    this.averageRating = null;
    this.ageLimit = 0;
    this.webpageUrl = null;
    this.categories = null;
    this.tags = null;
    this.playableInEmbed = false;
    this.liveStatus = null;
    this.releaseTimestamp = null;
    this.formatSortFields = null;
    this.commentCount = 0L;
    this.chapters = null;
    this.heatmap = null;
    this.likeCount = 0L;
    this.channel = null;
    this.channelFollowerCount = 0L;
    this.channelVerified = false;
    this.uploader = null;
    this.uploaderId = null;
    this.uploaderUrl = null;
    this.uploadDate = null;
    this.timestamp = 0;
    this.availability = null;
    this.originalUrl = null;
    this.webpageUrlBasename = null;
    this.webpageUrlDomain = null;
    this.extractor = null;
    this.extractorKey = null;
    this.playlist = null;
    this.playlistIndex = null;
    this.displayId = null;
    this.fullTitle = null;
    this.durationString = null;
    this.releaseYear = null;
    this.live = false;
    this.wasLive = false;
    this.requestedSubtitles = null;
    this.anyStreamDrmProtected = null;
    this.epoch = 0;
    this.audioSampleRate = 0;
    this.fileSize = null;
    this.formatId = null;
    this.formatNote = null;
    this.sourcePreference = 0;
    this.framesPerSecond = 0.0;
    this.audioChannels = 0;
    this.height = 0;
    this.quality = 0.0;
    this.drmProtected = false;
    this.totalBitrate = 0.0;
    this.approximateFileSize = 0L;
    this.url = null;
    this.width = 0;
    this.language = null;
    this.languagePreference = 0;
    this.preference = null;
    this.extension = null;
    this.videoCodec = null;
    this.audioCodec = null;
    this.dynamicRange = null;
    this.downloaderOptions = null;
    this.protocol = null;
    this.resolution = null;
    this.aspectRatio = 0.0;
    this.httpHeaders = null;
    this.videoExtension = null;
    this.audioExtension = null;
    this.videoBitrate = null;
    this.audioBitrate = null;
    this.format = null;
    this.fileName = null;
    this.fileNameAlias = null;
    this.type = null;
    this.version = null;
  }

  /**
   * Returns metadata for a source that yt-dlp did not resolve, such as a local file or a capture device, with only a
   * title.
   *
   * @param title the title to show for the source
   * @return the metadata
   * @throws NullPointerException if {@code title} is null
   * @since 1.0.0
   */
  public static UrlParseDump of(final String title) {
    Preconditions.checkNotNull(title, TITLE_NULL_MESSAGE);

    return new UrlParseDump(title, null, 0.0);
  }

  /**
   * Returns metadata for a source that yt-dlp did not resolve, such as a local file or a capture device, with only a
   * title and a description.
   *
   * @param title       the title to show for the source
   * @param description the description to show for the source
   * @return the metadata
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  public static UrlParseDump of(final String title, final String description) {
    return of(title, description, Duration.ZERO);
  }

  /**
   * Returns metadata for a source that yt-dlp did not resolve, such as a local file, with a title, a description and
   * the length that {@link #getDuration()} reports in seconds.
   *
   * @param title       the title to show for the source
   * @param description the description to show for the source
   * @param duration    how long the source plays, or {@link Duration#ZERO} when that is unknown
   * @return the metadata
   * @throws NullPointerException     if an argument is null
   * @throws IllegalArgumentException if {@code duration} is negative
   * @since 1.0.0
   */
  public static UrlParseDump of(final String title, final String description, final Duration duration) {
    Preconditions.checkNotNull(title, TITLE_NULL_MESSAGE);
    Preconditions.checkNotNull(description, "Description must not be null!");
    Preconditions.checkNotNull(duration, "Duration must not be null!");
    Preconditions.checkArgument(!duration.isNegative(), "Duration %s must not be negative!", duration);

    final long wholeSeconds = duration.toSeconds();
    final int nanoseconds = duration.toNanosPart();
    final double seconds = wholeSeconds + nanoseconds / NANOSECONDS_PER_SECOND;
    return new UrlParseDump(title, description, seconds);
  }

  /**
   * Returns the identifier of the video on its site, such as the YouTube video ID, or empty if yt-dlp did not
   * report it.
   *
   * @return the identifier of the video on its site, such as the YouTube video ID, or empty if yt-dlp did not
   * report it
   * @since 1.0.0
   */
  public Optional<String> getId() {
    return Optional.ofNullable(this.id);
  }

  /**
   * Returns the title of the video, or empty if yt-dlp did not report it.
   *
   * @return the title of the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getTitle() {
    return Optional.ofNullable(this.title);
  }

  /**
   * Returns every stream of the video, listed from worst to best, or an empty list if yt-dlp did not report any.
   * Pick one with a {@link me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy}.
   *
   * @return every stream of the video, listed from worst to best, or an empty list if yt-dlp did not report any
   * @since 1.0.0
   */
  public List<Format> getFormats() {
    if (this.formats == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.formats);
  }

  /**
   * Returns the thumbnails of the video in their available sizes, or an empty list if yt-dlp did not report any.
   *
   * @return the thumbnails of the video in their available sizes, or an empty list if yt-dlp did not report any
   * @since 1.0.0
   */
  public List<Thumbnail> getThumbnails() {
    if (this.thumbnails == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.thumbnails);
  }

  /**
   * Returns the URL of the best thumbnail of the video, or empty if yt-dlp did not report it.
   *
   * @return the URL of the best thumbnail of the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getThumbnail() {
    return Optional.ofNullable(this.thumbnail);
  }

  /**
   * Returns the description of the video, or empty if yt-dlp did not report it.
   *
   * @return the description of the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getDescription() {
    return Optional.ofNullable(this.description);
  }

  /**
   * Returns the identifier of the channel that published the video, or empty if yt-dlp did not report it.
   *
   * @return the identifier of the channel that published the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getChannelId() {
    return Optional.ofNullable(this.channelId);
  }

  /**
   * Returns the URL of the channel that published the video, or empty if yt-dlp did not report it.
   *
   * @return the URL of the channel that published the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getChannelUrl() {
    return Optional.ofNullable(this.channelUrl);
  }

  /**
   * Returns the length of the video in seconds, which yt-dlp can report with a fraction, or zero for a running live
   * stream and for a source whose length is unknown.
   *
   * @return the length of the video in seconds, which yt-dlp can report with a fraction, or zero for a running live
   * stream and for a source whose length is unknown
   * @since 1.0.0
   */
  public double getDuration() {
    return this.duration;
  }

  /**
   * Returns the number of views.
   *
   * @return the number of views
   * @since 1.0.0
   */
  public long getViewCount() {
    return this.viewCount;
  }

  /**
   * Returns the average rating of the video, or empty if the site does not rate videos.
   *
   * @return the average rating of the video, or empty if the site does not rate videos
   * @since 1.0.0
   */
  public Optional<Object> getAverageRating() {
    return Optional.ofNullable(this.averageRating);
  }

  /**
   * Returns the minimum age required to watch the video, or zero if there is none.
   *
   * @return the minimum age required to watch the video, or zero if there is none
   * @since 1.0.0
   */
  public int getAgeLimit() {
    return this.ageLimit;
  }

  /**
   * Returns the canonical URL of the page of the video, or empty if yt-dlp did not report it.
   *
   * @return the canonical URL of the page of the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getWebpageUrl() {
    return Optional.ofNullable(this.webpageUrl);
  }

  /**
   * Returns the categories the video is listed in, or an empty list if yt-dlp did not report any.
   *
   * @return the categories the video is listed in, or an empty list if yt-dlp did not report any
   * @since 1.0.0
   */
  public List<String> getCategories() {
    if (this.categories == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.categories);
  }

  /**
   * Returns the tags of the video, or an empty list if yt-dlp did not report any.
   *
   * @return the tags of the video, or an empty list if yt-dlp did not report any
   * @since 1.0.0
   */
  public List<String> getTags() {
    if (this.tags == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.tags);
  }

  /**
   * Returns whether the video can be played in a player embedded in another page.
   *
   * @return whether the video can be played in a player embedded in another page
   * @since 1.0.0
   */
  public boolean isPlayableInEmbed() {
    return this.playableInEmbed;
  }

  /**
   * Returns the live status of the video, such as {@code not_live}, {@code is_live}, or {@code was_live}, or empty
   * if yt-dlp did not report it.
   *
   * @return the live status of the video, such as {@code not_live}, {@code is_live}, or {@code was_live}, or empty
   * if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getLiveStatus() {
    return Optional.ofNullable(this.liveStatus);
  }

  /**
   * Returns the release time in seconds since the Unix epoch, or empty if unknown.
   *
   * @return the release time in seconds since the Unix epoch, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getReleaseTimestamp() {
    return Optional.ofNullable(this.releaseTimestamp);
  }

  /**
   * Returns the fields yt-dlp sorted the streams by, such as {@code quality} and {@code res}, or an empty list if
   * yt-dlp did not report any.
   *
   * @return the fields yt-dlp sorted the streams by, such as {@code quality} and {@code res}, or an empty list if
   * yt-dlp did not report any
   * @since 1.0.0
   */
  public List<String> getFormatSortFields() {
    if (this.formatSortFields == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.formatSortFields);
  }

  /**
   * Returns the number of comments.
   *
   * @return the number of comments
   * @since 1.0.0
   */
  public long getCommentCount() {
    return this.commentCount;
  }

  /**
   * Returns the chapters of the video, or empty if it has none.
   *
   * @return the chapters of the video, or empty if it has none
   * @since 1.0.0
   */
  public Optional<Object> getChapters() {
    return Optional.ofNullable(this.chapters);
  }

  /**
   * Returns the replay heat map of the video, or an empty list if the site does not report one.
   *
   * @return the replay heat map of the video, or an empty list if the site does not report one
   * @since 1.0.0
   */
  public List<Heatmap> getHeatmap() {
    if (this.heatmap == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.heatmap);
  }

  /**
   * Returns the number of likes.
   *
   * @return the number of likes
   * @since 1.0.0
   */
  public long getLikeCount() {
    return this.likeCount;
  }

  /**
   * Returns the name of the channel that published the video, or empty if yt-dlp did not report it.
   *
   * @return the name of the channel that published the video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getChannel() {
    return Optional.ofNullable(this.channel);
  }

  /**
   * Returns the number of followers of the channel.
   *
   * @return the number of followers of the channel
   * @since 1.0.0
   */
  public long getChannelFollowerCount() {
    return this.channelFollowerCount;
  }

  /**
   * Returns whether the site verified the channel.
   *
   * @return whether the site verified the channel
   * @since 1.0.0
   */
  public boolean isChannelVerified() {
    return this.channelVerified;
  }

  /**
   * Returns the name of the uploader, or empty if yt-dlp did not report it.
   *
   * @return the name of the uploader, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getUploader() {
    return Optional.ofNullable(this.uploader);
  }

  /**
   * Returns the identifier of the uploader, such as {@code @example}, or empty if yt-dlp did not report it.
   *
   * @return the identifier of the uploader, such as {@code @example}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getUploaderId() {
    return Optional.ofNullable(this.uploaderId);
  }

  /**
   * Returns the URL of the page of the uploader, or empty if yt-dlp did not report it.
   *
   * @return the URL of the page of the uploader, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getUploaderUrl() {
    return Optional.ofNullable(this.uploaderUrl);
  }

  /**
   * Returns the upload date in the form {@code YYYYMMDD}, or empty if yt-dlp did not report it.
   *
   * @return the upload date in the form {@code YYYYMMDD}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getUploadDate() {
    return Optional.ofNullable(this.uploadDate);
  }

  /**
   * Returns the upload time in seconds since the Unix epoch, or zero if unknown.
   *
   * @return the upload time in seconds since the Unix epoch, or zero if unknown
   * @since 1.0.0
   */
  public int getTimestamp() {
    return this.timestamp;
  }

  /**
   * Returns who can watch the video, such as {@code public}, {@code unlisted}, or {@code private}, or empty if
   * yt-dlp did not report it.
   *
   * @return who can watch the video, such as {@code public}, {@code unlisted}, or {@code private}, or empty if
   * yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getAvailability() {
    return Optional.ofNullable(this.availability);
  }

  /**
   * Returns the URL yt-dlp was given, before redirects and normalization, or empty if yt-dlp did not report it.
   *
   * @return the URL yt-dlp was given, before redirects and normalization, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getOriginalUrl() {
    return Optional.ofNullable(this.originalUrl);
  }

  /**
   * Returns the last path segment of {@link #getWebpageUrl()}, such as {@code watch}, or empty if yt-dlp did not
   * report it.
   *
   * @return the last path segment of {@link #getWebpageUrl()}, such as {@code watch}, or empty if yt-dlp did not
   * report it
   * @since 1.0.0
   */
  public Optional<String> getWebpageUrlBasename() {
    return Optional.ofNullable(this.webpageUrlBasename);
  }

  /**
   * Returns the domain of {@link #getWebpageUrl()}, such as {@code youtube.com}, or empty if yt-dlp did not report
   * it.
   *
   * @return the domain of {@link #getWebpageUrl()}, such as {@code youtube.com}, or empty if yt-dlp did not report
   * it
   * @since 1.0.0
   */
  public Optional<String> getWebpageUrlDomain() {
    return Optional.ofNullable(this.webpageUrlDomain);
  }

  /**
   * Returns the name of the yt-dlp extractor that resolved the page, such as {@code youtube}, or empty if yt-dlp
   * did not report it.
   *
   * @return the name of the yt-dlp extractor that resolved the page, such as {@code youtube}, or empty if yt-dlp
   * did not report it
   * @since 1.0.0
   */
  public Optional<String> getExtractor() {
    return Optional.ofNullable(this.extractor);
  }

  /**
   * Returns the key of the yt-dlp extractor that resolved the page, such as {@code Youtube}, or empty if yt-dlp did
   * not report it.
   *
   * @return the key of the yt-dlp extractor that resolved the page, such as {@code Youtube}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getExtractorKey() {
    return Optional.ofNullable(this.extractorKey);
  }

  /**
   * Returns the title of the playlist the video was resolved from, or empty for a single video.
   *
   * @return the title of the playlist the video was resolved from, or empty for a single video
   * @since 1.0.0
   */
  public Optional<Object> getPlaylist() {
    return Optional.ofNullable(this.playlist);
  }

  /**
   * Returns the position of the video in its playlist, or empty for a single video.
   *
   * @return the position of the video in its playlist, or empty for a single video
   * @since 1.0.0
   */
  public Optional<Object> getPlaylistIndex() {
    return Optional.ofNullable(this.playlistIndex);
  }

  /**
   * Returns the identifier of the video as the site displays it, or empty if yt-dlp did not report it.
   *
   * @return the identifier of the video as the site displays it, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getDisplayId() {
    return Optional.ofNullable(this.displayId);
  }

  /**
   * Returns the complete title of the video, which yt-dlp never shortens, or empty if yt-dlp did not report it.
   *
   * @return the complete title of the video, which yt-dlp never shortens, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFullTitle() {
    return Optional.ofNullable(this.fullTitle);
  }

  /**
   * Returns the length of the video as text, such as {@code 3:32}, or empty if yt-dlp did not report it.
   *
   * @return the length of the video as text, such as {@code 3:32}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getDurationString() {
    return Optional.ofNullable(this.durationString);
  }

  /**
   * Returns the year the video was released, or empty if unknown.
   *
   * @return the year the video was released, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getReleaseYear() {
    return Optional.ofNullable(this.releaseYear);
  }

  /**
   * Returns whether the video is a live stream that is still running.
   *
   * @return whether the video is a live stream that is still running
   * @since 1.0.0
   */
  public boolean isLive() {
    return this.live;
  }

  /**
   * Returns whether the video is the recording of a live stream that has ended.
   *
   * @return whether the video is the recording of a live stream that has ended
   * @since 1.0.0
   */
  public boolean wasLive() {
    return this.wasLive;
  }

  /**
   * Returns the subtitles yt-dlp would download, or empty because the library requests none.
   *
   * @return the subtitles yt-dlp would download, or empty because the library requests none
   * @since 1.0.0
   */
  public Optional<Object> getRequestedSubtitles() {
    return Optional.ofNullable(this.requestedSubtitles);
  }

  /**
   * Returns whether any stream of the video is protected by DRM, or empty if unknown.
   *
   * @return whether any stream of the video is protected by DRM, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getAnyStreamDrmProtected() {
    return Optional.ofNullable(this.anyStreamDrmProtected);
  }

  /**
   * Returns the time yt-dlp resolved the page, in seconds since the Unix epoch.
   *
   * @return the time yt-dlp resolved the page, in seconds since the Unix epoch
   * @since 1.0.0
   */
  public int getEpoch() {
    return this.epoch;
  }

  /**
   * Returns the audio sample rate of the default stream in hertz, or zero if unknown.
   *
   * @return the audio sample rate of the default stream in hertz, or zero if unknown
   * @since 1.0.0
   */
  public int getAudioSampleRate() {
    return this.audioSampleRate;
  }

  /**
   * Returns the exact size of the default stream in bytes, or empty if unknown.
   *
   * @return the exact size of the default stream in bytes, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getFileSize() {
    return Optional.ofNullable(this.fileSize);
  }

  /**
   * Returns the identifier of the default stream, such as {@code 137+251} for a merged video and audio stream, or
   * empty if yt-dlp did not report it.
   *
   * @return the identifier of the default stream, such as {@code 137+251} for a merged video and audio stream, or
   * empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFormatId() {
    return Optional.ofNullable(this.formatId);
  }

  /**
   * Returns a short readable note about the default stream, such as {@code 1080p+medium}, or empty if yt-dlp did
   * not report it.
   *
   * @return a short readable note about the default stream, such as {@code 1080p+medium}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getFormatNote() {
    return Optional.ofNullable(this.formatNote);
  }

  /**
   * Returns how strongly yt-dlp prefers the source of the default stream, where higher is better.
   *
   * @return how strongly yt-dlp prefers the source of the default stream, where higher is better
   * @since 1.0.0
   */
  public int getSourcePreference() {
    return this.sourcePreference;
  }

  /**
   * Returns the frame rate of the default stream in frames per second.
   *
   * @return the frame rate of the default stream in frames per second
   * @since 1.0.0
   */
  public double getFramesPerSecond() {
    return this.framesPerSecond;
  }

  /**
   * Returns the number of audio channels of the default stream.
   *
   * @return the number of audio channels of the default stream
   * @since 1.0.0
   */
  public int getAudioChannels() {
    return this.audioChannels;
  }

  /**
   * Returns the height of the default stream in pixels.
   *
   * @return the height of the default stream in pixels
   * @since 1.0.0
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Returns the quality rank of the default stream, where higher is better.
   *
   * @return the quality rank of the default stream, where higher is better
   * @since 1.0.0
   */
  public double getQuality() {
    return this.quality;
  }

  /**
   * Returns whether the default stream is protected by DRM.
   *
   * @return whether the default stream is protected by DRM
   * @since 1.0.0
   */
  public boolean isDrmProtected() {
    return this.drmProtected;
  }

  /**
   * Returns the average total bitrate of the default stream in kilobits per second.
   *
   * @return the average total bitrate of the default stream in kilobits per second
   * @since 1.0.0
   */
  public double getTotalBitrate() {
    return this.totalBitrate;
  }

  /**
   * Returns the size of the default stream in bytes as estimated from its bitrate and duration, or zero if unknown.
   *
   * @return the size of the default stream in bytes as estimated from its bitrate and duration, or zero if unknown
   * @since 1.0.0
   */
  public long getApproximateFileSize() {
    return this.approximateFileSize;
  }

  /**
   * Returns the direct URL of the default stream, or empty if it merges several streams.
   *
   * @return the direct URL of the default stream, or empty if it merges several streams
   * @since 1.0.0
   */
  public Optional<String> getUrl() {
    return Optional.ofNullable(this.url);
  }

  /**
   * Returns the width of the default stream in pixels.
   *
   * @return the width of the default stream in pixels
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Returns the language code of the audio of the default stream, such as {@code en}, or empty if unknown.
   *
   * @return the language code of the audio of the default stream, such as {@code en}, or empty if unknown
   * @since 1.0.0
   */
  public Optional<String> getLanguage() {
    return Optional.ofNullable(this.language);
  }

  /**
   * Returns how strongly yt-dlp prefers the language of the default stream, where higher is better.
   *
   * @return how strongly yt-dlp prefers the language of the default stream, where higher is better
   * @since 1.0.0
   */
  public int getLanguagePreference() {
    return this.languagePreference;
  }

  /**
   * Returns how strongly yt-dlp prefers the default stream, or empty for no preference.
   *
   * @return how strongly yt-dlp prefers the default stream, or empty for no preference
   * @since 1.0.0
   */
  public Optional<Object> getPreference() {
    return Optional.ofNullable(this.preference);
  }

  /**
   * Returns the file extension of the default stream, such as {@code mp4}, or empty if yt-dlp did not report it.
   *
   * @return the file extension of the default stream, such as {@code mp4}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getExtension() {
    return Optional.ofNullable(this.extension);
  }

  /**
   * Returns the video codec of the default stream, such as {@code avc1.640028}, or {@code none}, or empty if yt-dlp
   * did not report it.
   *
   * @return the video codec of the default stream, such as {@code avc1.640028}, or {@code none}, or empty if yt-dlp
   * did not report it
   * @since 1.0.0
   */
  public Optional<String> getVideoCodec() {
    return Optional.ofNullable(this.videoCodec);
  }

  /**
   * Returns the audio codec of the default stream, such as {@code opus}, or {@code none}, or empty if yt-dlp did
   * not report it.
   *
   * @return the audio codec of the default stream, such as {@code opus}, or {@code none}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getAudioCodec() {
    return Optional.ofNullable(this.audioCodec);
  }

  /**
   * Returns the dynamic range of the default stream, such as {@code SDR}, or empty if it has no video.
   *
   * @return the dynamic range of the default stream, such as {@code SDR}, or empty if it has no video
   * @since 1.0.0
   */
  public Optional<String> getDynamicRange() {
    return Optional.ofNullable(this.dynamicRange);
  }

  /**
   * Returns hints for downloading the default stream, or empty if there are none.
   *
   * @return hints for downloading the default stream, or empty if there are none
   * @since 1.0.0
   */
  public Optional<DownloaderOptions> getDownloaderOptions() {
    return Optional.ofNullable(this.downloaderOptions);
  }

  /**
   * Returns the protocol of the default stream, such as {@code https+https} for a merge of two streams, or empty if
   * yt-dlp did not report it.
   *
   * @return the protocol of the default stream, such as {@code https+https} for a merge of two streams, or empty if
   * yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getProtocol() {
    return Optional.ofNullable(this.protocol);
  }

  /**
   * Returns the resolution of the default stream as text, such as {@code 1920x1080}, or empty if yt-dlp did not
   * report it.
   *
   * @return the resolution of the default stream as text, such as {@code 1920x1080}, or empty if yt-dlp did not
   * report it
   * @since 1.0.0
   */
  public Optional<String> getResolution() {
    return Optional.ofNullable(this.resolution);
  }

  /**
   * Returns the width of the default stream divided by its height.
   *
   * @return the width of the default stream divided by its height
   * @since 1.0.0
   */
  public double getAspectRatio() {
    return this.aspectRatio;
  }

  /**
   * Returns the HTTP headers that requests for the default stream must send, or empty if no headers were reported.
   *
   * @return the HTTP headers that requests for the default stream must send, or empty if no headers were reported
   * @since 1.0.0
   */
  public Optional<HttpHeaders> getHttpHeaders() {
    return Optional.ofNullable(this.httpHeaders);
  }

  /**
   * Returns the video extension of the default stream, such as {@code mp4}, or {@code none}, or empty if yt-dlp did
   * not report it.
   *
   * @return the video extension of the default stream, such as {@code mp4}, or {@code none}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getVideoExtension() {
    return Optional.ofNullable(this.videoExtension);
  }

  /**
   * Returns the audio extension of the default stream, such as {@code m4a}, or {@code none}, or empty if yt-dlp did
   * not report it.
   *
   * @return the audio extension of the default stream, such as {@code m4a}, or {@code none}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getAudioExtension() {
    return Optional.ofNullable(this.audioExtension);
  }

  /**
   * Returns the average video bitrate of the default stream in kilobits per second, or empty if unknown.
   *
   * @return the average video bitrate of the default stream in kilobits per second, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getVideoBitrate() {
    return Optional.ofNullable(this.videoBitrate);
  }

  /**
   * Returns the average audio bitrate of the default stream in kilobits per second, or empty if unknown.
   *
   * @return the average audio bitrate of the default stream in kilobits per second, or empty if unknown
   * @since 1.0.0
   */
  public Optional<Object> getAudioBitrate() {
    return Optional.ofNullable(this.audioBitrate);
  }

  /**
   * Returns a readable description of the default stream, such as {@code 137 - 1920x1080 (1080p)+251 - audio only},
   * or empty if yt-dlp did not report it.
   *
   * @return a readable description of the default stream, such as {@code 137 - 1920x1080 (1080p)+251 - audio only},
   * or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFormat() {
    return Optional.ofNullable(this.format);
  }

  /**
   * Returns the file name yt-dlp would save the default stream as, or empty if yt-dlp did not report it.
   *
   * @return the file name yt-dlp would save the default stream as, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFileName() {
    return Optional.ofNullable(this.fileName);
  }

  /**
   * Returns the file name yt-dlp would save the default stream as, the same value as {@link #getFileName()}, or
   * empty if yt-dlp did not report it.
   *
   * @return the file name yt-dlp would save the default stream as, the same value as {@link #getFileName()}, or
   * empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFileNameAlias() {
    return Optional.ofNullable(this.fileNameAlias);
  }

  /**
   * Returns the kind of result, such as {@code video} or {@code playlist}, or empty if yt-dlp did not report it.
   *
   * @return the kind of result, such as {@code video} or {@code playlist}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getType() {
    return Optional.ofNullable(this.type);
  }

  /**
   * Returns the version of yt-dlp that produced the metadata, or empty if yt-dlp did not report it.
   *
   * @return the version of yt-dlp that produced the metadata, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<Version> getVersion() {
    return Optional.ofNullable(this.version);
  }

  /**
   * Returns whether {@code other} is a dump with the same values.
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

    if (!(other instanceof final UrlParseDump urlParseDump)) {
      return false;
    }

    return (
      Objects.equals(this.id, urlParseDump.id) &&
      Objects.equals(this.title, urlParseDump.title) &&
      Objects.equals(this.formats, urlParseDump.formats) &&
      Objects.equals(this.thumbnails, urlParseDump.thumbnails) &&
      Objects.equals(this.thumbnail, urlParseDump.thumbnail) &&
      Objects.equals(this.description, urlParseDump.description) &&
      Objects.equals(this.channelId, urlParseDump.channelId) &&
      Objects.equals(this.channelUrl, urlParseDump.channelUrl) &&
      Double.compare(this.duration, urlParseDump.duration) == 0 &&
      this.viewCount == urlParseDump.viewCount &&
      Objects.equals(this.averageRating, urlParseDump.averageRating) &&
      this.ageLimit == urlParseDump.ageLimit &&
      Objects.equals(this.webpageUrl, urlParseDump.webpageUrl) &&
      Objects.equals(this.categories, urlParseDump.categories) &&
      Objects.equals(this.tags, urlParseDump.tags) &&
      this.playableInEmbed == urlParseDump.playableInEmbed &&
      Objects.equals(this.liveStatus, urlParseDump.liveStatus) &&
      Objects.equals(this.releaseTimestamp, urlParseDump.releaseTimestamp) &&
      Objects.equals(this.formatSortFields, urlParseDump.formatSortFields) &&
      this.commentCount == urlParseDump.commentCount &&
      Objects.equals(this.chapters, urlParseDump.chapters) &&
      Objects.equals(this.heatmap, urlParseDump.heatmap) &&
      this.likeCount == urlParseDump.likeCount &&
      Objects.equals(this.channel, urlParseDump.channel) &&
      this.channelFollowerCount == urlParseDump.channelFollowerCount &&
      this.channelVerified == urlParseDump.channelVerified &&
      Objects.equals(this.uploader, urlParseDump.uploader) &&
      Objects.equals(this.uploaderId, urlParseDump.uploaderId) &&
      Objects.equals(this.uploaderUrl, urlParseDump.uploaderUrl) &&
      Objects.equals(this.uploadDate, urlParseDump.uploadDate) &&
      this.timestamp == urlParseDump.timestamp &&
      Objects.equals(this.availability, urlParseDump.availability) &&
      Objects.equals(this.originalUrl, urlParseDump.originalUrl) &&
      Objects.equals(this.webpageUrlBasename, urlParseDump.webpageUrlBasename) &&
      Objects.equals(this.webpageUrlDomain, urlParseDump.webpageUrlDomain) &&
      Objects.equals(this.extractor, urlParseDump.extractor) &&
      Objects.equals(this.extractorKey, urlParseDump.extractorKey) &&
      Objects.equals(this.playlist, urlParseDump.playlist) &&
      Objects.equals(this.playlistIndex, urlParseDump.playlistIndex) &&
      Objects.equals(this.displayId, urlParseDump.displayId) &&
      Objects.equals(this.fullTitle, urlParseDump.fullTitle) &&
      Objects.equals(this.durationString, urlParseDump.durationString) &&
      Objects.equals(this.releaseYear, urlParseDump.releaseYear) &&
      this.live == urlParseDump.live &&
      this.wasLive == urlParseDump.wasLive &&
      Objects.equals(this.requestedSubtitles, urlParseDump.requestedSubtitles) &&
      Objects.equals(this.anyStreamDrmProtected, urlParseDump.anyStreamDrmProtected) &&
      this.epoch == urlParseDump.epoch &&
      this.audioSampleRate == urlParseDump.audioSampleRate &&
      Objects.equals(this.fileSize, urlParseDump.fileSize) &&
      Objects.equals(this.formatId, urlParseDump.formatId) &&
      Objects.equals(this.formatNote, urlParseDump.formatNote) &&
      this.sourcePreference == urlParseDump.sourcePreference &&
      Double.compare(this.framesPerSecond, urlParseDump.framesPerSecond) == 0 &&
      this.audioChannels == urlParseDump.audioChannels &&
      this.height == urlParseDump.height &&
      Double.compare(this.quality, urlParseDump.quality) == 0 &&
      this.drmProtected == urlParseDump.drmProtected &&
      Double.compare(this.totalBitrate, urlParseDump.totalBitrate) == 0 &&
      this.approximateFileSize == urlParseDump.approximateFileSize &&
      Objects.equals(this.url, urlParseDump.url) &&
      this.width == urlParseDump.width &&
      Objects.equals(this.language, urlParseDump.language) &&
      this.languagePreference == urlParseDump.languagePreference &&
      Objects.equals(this.preference, urlParseDump.preference) &&
      Objects.equals(this.extension, urlParseDump.extension) &&
      Objects.equals(this.videoCodec, urlParseDump.videoCodec) &&
      Objects.equals(this.audioCodec, urlParseDump.audioCodec) &&
      Objects.equals(this.dynamicRange, urlParseDump.dynamicRange) &&
      Objects.equals(this.downloaderOptions, urlParseDump.downloaderOptions) &&
      Objects.equals(this.protocol, urlParseDump.protocol) &&
      Objects.equals(this.resolution, urlParseDump.resolution) &&
      Double.compare(this.aspectRatio, urlParseDump.aspectRatio) == 0 &&
      Objects.equals(this.httpHeaders, urlParseDump.httpHeaders) &&
      Objects.equals(this.videoExtension, urlParseDump.videoExtension) &&
      Objects.equals(this.audioExtension, urlParseDump.audioExtension) &&
      Objects.equals(this.videoBitrate, urlParseDump.videoBitrate) &&
      Objects.equals(this.audioBitrate, urlParseDump.audioBitrate) &&
      Objects.equals(this.format, urlParseDump.format) &&
      Objects.equals(this.fileName, urlParseDump.fileName) &&
      Objects.equals(this.fileNameAlias, urlParseDump.fileNameAlias) &&
      Objects.equals(this.type, urlParseDump.type) &&
      Objects.equals(this.version, urlParseDump.version)
    );
  }

  /**
   * Returns a hash code built from every field of this dump.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(
      this.id,
      this.title,
      this.formats,
      this.thumbnails,
      this.thumbnail,
      this.description,
      this.channelId,
      this.channelUrl,
      this.duration,
      this.viewCount,
      this.averageRating,
      this.ageLimit,
      this.webpageUrl,
      this.categories,
      this.tags,
      this.playableInEmbed,
      this.liveStatus,
      this.releaseTimestamp,
      this.formatSortFields,
      this.commentCount,
      this.chapters,
      this.heatmap,
      this.likeCount,
      this.channel,
      this.channelFollowerCount,
      this.channelVerified,
      this.uploader,
      this.uploaderId,
      this.uploaderUrl,
      this.uploadDate,
      this.timestamp,
      this.availability,
      this.originalUrl,
      this.webpageUrlBasename,
      this.webpageUrlDomain,
      this.extractor,
      this.extractorKey,
      this.playlist,
      this.playlistIndex,
      this.displayId,
      this.fullTitle,
      this.durationString,
      this.releaseYear,
      this.live,
      this.wasLive,
      this.requestedSubtitles,
      this.anyStreamDrmProtected,
      this.epoch,
      this.audioSampleRate,
      this.fileSize,
      this.formatId,
      this.formatNote,
      this.sourcePreference,
      this.framesPerSecond,
      this.audioChannels,
      this.height,
      this.quality,
      this.drmProtected,
      this.totalBitrate,
      this.approximateFileSize,
      this.url,
      this.width,
      this.language,
      this.languagePreference,
      this.preference,
      this.extension,
      this.videoCodec,
      this.audioCodec,
      this.dynamicRange,
      this.downloaderOptions,
      this.protocol,
      this.resolution,
      this.aspectRatio,
      this.httpHeaders,
      this.videoExtension,
      this.audioExtension,
      this.videoBitrate,
      this.audioBitrate,
      this.format,
      this.fileName,
      this.fileNameAlias,
      this.type,
      this.version
    );
  }

  /**
   * Returns a debugging representation with every field of this dump.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("id", this.id)
      .add("title", this.title)
      .add("formats", this.formats)
      .add("thumbnails", this.thumbnails)
      .add("thumbnail", this.thumbnail)
      .add("description", this.description)
      .add("channelId", this.channelId)
      .add("channelUrl", this.channelUrl)
      .add("duration", this.duration)
      .add("viewCount", this.viewCount)
      .add("averageRating", this.averageRating)
      .add("ageLimit", this.ageLimit)
      .add("webpageUrl", this.webpageUrl)
      .add("categories", this.categories)
      .add("tags", this.tags)
      .add("playableInEmbed", this.playableInEmbed)
      .add("liveStatus", this.liveStatus)
      .add("releaseTimestamp", this.releaseTimestamp)
      .add("formatSortFields", this.formatSortFields)
      .add("commentCount", this.commentCount)
      .add("chapters", this.chapters)
      .add("heatmap", this.heatmap)
      .add("likeCount", this.likeCount)
      .add("channel", this.channel)
      .add("channelFollowerCount", this.channelFollowerCount)
      .add("channelVerified", this.channelVerified)
      .add("uploader", this.uploader)
      .add("uploaderId", this.uploaderId)
      .add("uploaderUrl", this.uploaderUrl)
      .add("uploadDate", this.uploadDate)
      .add("timestamp", this.timestamp)
      .add("availability", this.availability)
      .add("originalUrl", this.originalUrl)
      .add("webpageUrlBasename", this.webpageUrlBasename)
      .add("webpageUrlDomain", this.webpageUrlDomain)
      .add("extractor", this.extractor)
      .add("extractorKey", this.extractorKey)
      .add("playlist", this.playlist)
      .add("playlistIndex", this.playlistIndex)
      .add("displayId", this.displayId)
      .add("fullTitle", this.fullTitle)
      .add("durationString", this.durationString)
      .add("releaseYear", this.releaseYear)
      .add("live", this.live)
      .add("wasLive", this.wasLive)
      .add("requestedSubtitles", this.requestedSubtitles)
      .add("anyStreamDrmProtected", this.anyStreamDrmProtected)
      .add("epoch", this.epoch)
      .add("audioSampleRate", this.audioSampleRate)
      .add("fileSize", this.fileSize)
      .add("formatId", this.formatId)
      .add("formatNote", this.formatNote)
      .add("sourcePreference", this.sourcePreference)
      .add("framesPerSecond", this.framesPerSecond)
      .add("audioChannels", this.audioChannels)
      .add("height", this.height)
      .add("quality", this.quality)
      .add("drmProtected", this.drmProtected)
      .add("totalBitrate", this.totalBitrate)
      .add("approximateFileSize", this.approximateFileSize)
      .add("url", this.url)
      .add("width", this.width)
      .add("language", this.language)
      .add("languagePreference", this.languagePreference)
      .add("preference", this.preference)
      .add("extension", this.extension)
      .add("videoCodec", this.videoCodec)
      .add("audioCodec", this.audioCodec)
      .add("dynamicRange", this.dynamicRange)
      .add("downloaderOptions", this.downloaderOptions)
      .add("protocol", this.protocol)
      .add("resolution", this.resolution)
      .add("aspectRatio", this.aspectRatio)
      .add("httpHeaders", this.httpHeaders)
      .add("videoExtension", this.videoExtension)
      .add("audioExtension", this.audioExtension)
      .add("videoBitrate", this.videoBitrate)
      .add("audioBitrate", this.audioBitrate)
      .add("format", this.format)
      .add("fileName", this.fileName)
      .add("fileNameAlias", this.fileNameAlias)
      .add("type", this.type)
      .add("version", this.version)
      .toString();
  }

  static final class Deserializer implements JsonDeserializer<UrlParseDump> {

    @Override
    public UrlParseDump deserialize(final JsonElement json, final Type type, final JsonDeserializationContext context) {
      final @Nullable Fields parsed = context.deserialize(json, Fields.class);
      final Fields fields = requireNonNull(parsed); // checker framework
      return new UrlParseDump(fields);
    }
  }

  private record Fields(
    @Nullable String id,
    @Nullable String title,
    @Nullable List<Format> formats,
    @Nullable List<Thumbnail> thumbnails,
    @Nullable String thumbnail,
    @Nullable String description,
    @SerializedName("channel_id") @Nullable String channelId,
    @SerializedName("channel_url") @Nullable String channelUrl,
    @Nullable Double duration,
    @SerializedName("view_count") @Nullable Long viewCount,
    @SerializedName("average_rating") @Nullable Object averageRating,
    @SerializedName("age_limit") @Nullable Integer ageLimit,
    @SerializedName("webpage_url") @Nullable String webpageUrl,
    @Nullable List<String> categories,
    @Nullable List<String> tags,
    @SerializedName("playable_in_embed") @Nullable Boolean playableInEmbed,
    @SerializedName("live_status") @Nullable String liveStatus,
    @SerializedName("release_timestamp") @Nullable Object releaseTimestamp,
    @SerializedName("_format_sort_fields") @Nullable List<String> formatSortFields,
    @SerializedName("comment_count") @Nullable Long commentCount,
    @Nullable Object chapters,
    @Nullable List<Heatmap> heatmap,
    @SerializedName("like_count") @Nullable Long likeCount,
    @Nullable String channel,
    @SerializedName("channel_follower_count") @Nullable Long channelFollowerCount,
    @SerializedName("channel_is_verified") @Nullable Boolean channelVerified,
    @Nullable String uploader,
    @SerializedName("uploader_id") @Nullable String uploaderId,
    @SerializedName("uploader_url") @Nullable String uploaderUrl,
    @SerializedName("upload_date") @Nullable String uploadDate,
    @Nullable Integer timestamp,
    @Nullable String availability,
    @SerializedName("original_url") @Nullable String originalUrl,
    @SerializedName("webpage_url_basename") @Nullable String webpageUrlBasename,
    @SerializedName("webpage_url_domain") @Nullable String webpageUrlDomain,
    @Nullable String extractor,
    @SerializedName("extractor_key") @Nullable String extractorKey,
    @Nullable Object playlist,
    @SerializedName("playlist_index") @Nullable Object playlistIndex,
    @SerializedName("display_id") @Nullable String displayId,
    @SerializedName("fulltitle") @Nullable String fullTitle,
    @SerializedName("duration_string") @Nullable String durationString,
    @SerializedName("release_year") @Nullable Object releaseYear,
    @SerializedName("is_live") @Nullable Boolean live,
    @SerializedName("was_live") @Nullable Boolean wasLive,
    @SerializedName("requested_subtitles") @Nullable Object requestedSubtitles,
    @SerializedName("_has_drm") @Nullable Object anyStreamDrmProtected,
    @Nullable Integer epoch,
    @SerializedName("asr") @Nullable Integer audioSampleRate,
    @SerializedName("filesize") @Nullable Object fileSize,
    @SerializedName("format_id") @Nullable String formatId,
    @SerializedName("format_note") @Nullable String formatNote,
    @SerializedName("source_preference") @Nullable Integer sourcePreference,
    @SerializedName("fps") @Nullable Double framesPerSecond,
    @SerializedName("audio_channels") @Nullable Integer audioChannels,
    @Nullable Integer height,
    @Nullable Double quality,
    @SerializedName("has_drm") @Nullable Boolean drmProtected,
    @SerializedName("tbr") @Nullable Double totalBitrate,
    @SerializedName("filesize_approx") @Nullable Long approximateFileSize,
    @Nullable String url,
    @Nullable Integer width,
    @Nullable String language,
    @SerializedName("language_preference") @Nullable Integer languagePreference,
    @Nullable Object preference,
    @SerializedName("ext") @Nullable String extension,
    @SerializedName("vcodec") @Nullable String videoCodec,
    @SerializedName("acodec") @Nullable String audioCodec,
    @SerializedName("dynamic_range") @Nullable String dynamicRange,
    @SerializedName("downloader_options") @Nullable DownloaderOptions downloaderOptions,
    @Nullable String protocol,
    @Nullable String resolution,
    @SerializedName("aspect_ratio") @Nullable Double aspectRatio,
    @SerializedName("http_headers") @Nullable HttpHeaders httpHeaders,
    @SerializedName("video_ext") @Nullable String videoExtension,
    @SerializedName("audio_ext") @Nullable String audioExtension,
    @SerializedName("vbr") @Nullable Object videoBitrate,
    @SerializedName("abr") @Nullable Object audioBitrate,
    @Nullable String format,
    @SerializedName("_filename") @Nullable String fileName,
    @SerializedName("filename") @Nullable String fileNameAlias,
    @SerializedName("_type") @Nullable String type,
    @SerializedName("_version") @Nullable Version version
  ) {}
}
