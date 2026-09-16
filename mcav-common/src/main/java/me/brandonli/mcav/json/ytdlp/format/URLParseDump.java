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

import java.util.ArrayList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The metadata yt-dlp prints for a video with {@code --dump-json}: the details of the video, every available stream
 * in {@link #formats}, and the fields of the stream yt-dlp would download by default, which is often a merge of the
 * best video and the best audio stream. The fields mirror the JSON and are filled by Gson; fields yt-dlp did not
 * report are null or zero.
 */
public class URLParseDump {

  /**
   * Creates an empty dump in which every field is null or zero. Gson creates dumps this way before it fills them.
   */
  public URLParseDump() {
    // populated by Gson
  }

  /** The identifier of the video on its site, such as the YouTube video ID. */
  public @Nullable String id;

  /** The title of the video. */
  public @Nullable String title;

  /**
   * Every stream of the video, listed from worst to best. Pick one with a
   * {@link me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy}.
   */
  public @Nullable ArrayList<Format> formats;

  /** The thumbnails of the video in their available sizes. */
  public @Nullable ArrayList<Thumbnail> thumbnails;

  /** The URL of the best thumbnail of the video. */
  public @Nullable String thumbnail;

  /** The description of the video. */
  public @Nullable String description;

  /** The identifier of the channel that published the video. */
  public @Nullable String channel_id;

  /** The URL of the channel that published the video. */
  public @Nullable String channel_url;

  /** The length of the video in seconds, which yt-dlp can report with a fraction, or zero for a running live stream. */
  public double duration;

  /** The number of views. */
  public long view_count;

  /** The average rating of the video, or null if the site does not rate videos. */
  public @Nullable Object average_rating;

  /** The minimum age required to watch the video, or zero if there is none. */
  public int age_limit;

  /** The canonical URL of the page of the video. */
  public @Nullable String webpage_url;

  /** The categories the video is listed in. */
  public @Nullable ArrayList<String> categories;

  /** The tags of the video. */
  public @Nullable ArrayList<String> tags;

  /** Whether the video can be played in a player embedded in another page. */
  public boolean playable_in_embed;

  /** Whether the video is live, such as {@code not_live}, {@code is_live}, or {@code was_live}. */
  public @Nullable String live_status;

  /** The release time in seconds since the Unix epoch, or null if unknown. */
  public @Nullable Object release_timestamp;

  /** The fields yt-dlp sorted the streams by, such as {@code quality} and {@code res}. */
  public @Nullable ArrayList<String> _format_sort_fields;

  /** The number of comments. */
  public long comment_count;

  /** The chapters of the video, or null if it has none. */
  public @Nullable Object chapters;

  /** The replay heat map of the video, or null if the site does not report one. */
  public @Nullable ArrayList<Heatmap> heatmap;

  /** The number of likes. */
  public long like_count;

  /** The name of the channel that published the video. */
  public @Nullable String channel;

  /** The number of followers of the channel. */
  public long channel_follower_count;

  /** Whether the site verified the channel. */
  public boolean channel_is_verified;

  /** The name of the uploader. */
  public @Nullable String uploader;

  /** The identifier of the uploader, such as {@code @example}. */
  public @Nullable String uploader_id;

  /** The URL of the page of the uploader. */
  public @Nullable String uploader_url;

  /** The upload date in the form {@code YYYYMMDD}. */
  public @Nullable String upload_date;

  /** The upload time in seconds since the Unix epoch, or zero if unknown. */
  public int timestamp;

  /** Who can watch the video, such as {@code public}, {@code unlisted}, or {@code private}. */
  public @Nullable String availability;

  /** The URL yt-dlp was given, before redirects and normalization. */
  public @Nullable String original_url;

  /** The last path segment of {@link #webpage_url}, such as {@code watch}. */
  public @Nullable String webpage_url_basename;

  /** The domain of {@link #webpage_url}, such as {@code youtube.com}. */
  public @Nullable String webpage_url_domain;

  /** The name of the yt-dlp extractor that resolved the page, such as {@code youtube}. */
  public @Nullable String extractor;

  /** The key of the yt-dlp extractor that resolved the page, such as {@code Youtube}. */
  public @Nullable String extractor_key;

  /** The title of the playlist the video was resolved from, or null for a single video. */
  public @Nullable Object playlist;

  /** The position of the video in its playlist, or null for a single video. */
  public @Nullable Object playlist_index;

  /** The identifier of the video as the site displays it. */
  public @Nullable String display_id;

  /** The complete title of the video, which yt-dlp never shortens. */
  public @Nullable String fulltitle;

  /** The length of the video as text, such as {@code 3:32}. */
  public @Nullable String duration_string;

  /** The year the video was released, or null if unknown. */
  public @Nullable Object release_year;

  /** Whether the video is a live stream that is still running. */
  public boolean is_live;

  /** Whether the video is the recording of a live stream that has ended. */
  public boolean was_live;

  /** The subtitles yt-dlp would download, or null because the library requests none. */
  public @Nullable Object requested_subtitles;

  /** Whether any stream of the video is protected by DRM, or null if unknown. */
  public @Nullable Object _has_drm;

  /** The time yt-dlp resolved the page, in seconds since the Unix epoch. */
  public int epoch;

  /** The audio sample rate of the default stream in hertz, or zero if unknown. */
  public int asr;

  /** The exact size of the default stream in bytes, or null if unknown. */
  public @Nullable Object filesize;

  /** The identifier of the default stream, such as {@code 137+251} for a merged video and audio stream. */
  public @Nullable String format_id;

  /** A short readable note about the default stream, such as {@code 1080p+medium}. */
  public @Nullable String format_note;

  /** How strongly yt-dlp prefers the source of the default stream, where higher is better. */
  public int source_preference;

  /** The frame rate of the default stream in frames per second. */
  public double fps;

  /** The number of audio channels of the default stream. */
  public int audio_channels;

  /** The height of the default stream in pixels. */
  public int height;

  /** The quality rank of the default stream, where higher is better. */
  public double quality;

  /** Whether the default stream is protected by DRM. */
  public boolean has_drm;

  /** The average total bitrate of the default stream in kilobits per second. */
  public double tbr;

  /** The size of the default stream in bytes as estimated from its bitrate and duration, or zero if unknown. */
  public long filesize_approx;

  /** The direct URL of the default stream, or null if it merges several streams. */
  public @Nullable String url;

  /** The width of the default stream in pixels. */
  public int width;

  /** The language code of the audio of the default stream, such as {@code en}, or null if unknown. */
  public @Nullable String language;

  /** How strongly yt-dlp prefers the language of the default stream, where higher is better. */
  public int language_preference;

  /** How strongly yt-dlp prefers the default stream, or null for no preference. */
  public @Nullable Object preference;

  /** The file extension of the default stream, such as {@code mp4}. */
  public @Nullable String ext;

  /** The video codec of the default stream, such as {@code avc1.640028}, or {@code none}. */
  public @Nullable String vcodec;

  /** The audio codec of the default stream, such as {@code opus}, or {@code none}. */
  public @Nullable String acodec;

  /** The dynamic range of the default stream, such as {@code SDR}, or null if it has no video. */
  public @Nullable String dynamic_range;

  /** Hints for downloading the default stream, or null if there are none. */
  public @Nullable DownloaderOptions downloader_options;

  /** The protocol of the default stream, such as {@code https+https} for a merge of two streams. */
  public @Nullable String protocol;

  /** The resolution of the default stream as text, such as {@code 1920x1080}. */
  public @Nullable String resolution;

  /** The width of the default stream divided by its height. */
  public double aspect_ratio;

  /** The HTTP headers that requests for the default stream must send, or null if any request works. */
  public @Nullable HttpHeaders http_headers;

  /** The video extension of the default stream, such as {@code mp4}, or {@code none}. */
  public @Nullable String video_ext;

  /** The audio extension of the default stream, such as {@code m4a}, or {@code none}. */
  public @Nullable String audio_ext;

  /** The average video bitrate of the default stream in kilobits per second, or null if unknown. */
  public @Nullable Object vbr;

  /** The average audio bitrate of the default stream in kilobits per second, or null if unknown. */
  public @Nullable Object abr;

  /** A readable description of the default stream, such as {@code 137 - 1920x1080 (1080p)+251 - audio only}. */
  public @Nullable String format;

  /** The file name yt-dlp would save the default stream as. */
  public @Nullable String _filename;

  /** The file name yt-dlp would save the default stream as, the same value as {@link #_filename}. */
  public @Nullable String filename;

  /** The kind of result, such as {@code video} or {@code playlist}. */
  public @Nullable String _type;

  /** The version of yt-dlp that produced the metadata. */
  public @Nullable Version _version;
}
