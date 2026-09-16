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

import java.net.URI;
import java.util.ArrayList;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stream of a video as listed by yt-dlp, such as a 1080p MP4 video stream or an Opus audio stream. The fields
 * mirror the JSON yt-dlp prints and are filled by Gson; fields yt-dlp did not report are null or zero.
 */
public class Format {

  Format() {
    // populated by Gson
  }

  /** The identifier of the stream on its site, such as {@code 251}, which yt-dlp accepts in its format option. */
  public @Nullable String format_id;

  /** A short readable note about the stream, such as {@code 1080p} or {@code medium}. */
  public @Nullable String format_note;

  /** The file extension of the stream, such as {@code mp4} or {@code webm}. */
  public @Nullable String ext;

  /** The protocol of the stream: {@code https}, {@code m3u8_native} for HLS, or {@code http_dash_segments} for DASH. */
  public @Nullable String protocol;

  /** The audio codec, such as {@code opus}, or {@code none} for a stream without audio. */
  public @Nullable String acodec;

  /** The video codec, such as {@code avc1.640028}, or {@code none} for a stream without video. */
  public @Nullable String vcodec;

  /** The direct URL of the stream, which a player opens with the {@link #http_headers}. */
  public @Nullable String url;

  /** The width of the video in pixels, or zero for a stream without video. */
  public int width;

  /** The height of the video in pixels, or zero for a stream without video. */
  public int height;

  /** The frame rate of the video in frames per second, or zero for a stream without video. */
  public double fps;

  /** The number of thumbnail rows in each image of a storyboard stream, or zero for other streams. */
  public int rows;

  /** The number of thumbnail columns in each image of a storyboard stream, or zero for other streams. */
  public int columns;

  /** The fragments of a segmented stream, such as the images of a storyboard, or null for a stream served whole. */
  public @Nullable ArrayList<Fragment> fragments;

  /** The resolution as text, such as {@code 1920x1080}, or {@code audio only} for a stream without video. */
  public @Nullable String resolution;

  /** The width of the video divided by its height, or zero for a stream without video. */
  public double aspect_ratio;

  /** The size of the stream in bytes as estimated from its bitrate and duration, or zero if unknown. */
  public long filesize_approx;

  /** The HTTP headers that requests for the stream must send, or null if any request works. */
  public @Nullable HttpHeaders http_headers;

  /** The audio extension of an audio-only stream, such as {@code m4a}, or {@code none} for a stream with video. */
  public @Nullable String audio_ext;

  /** The video extension of the stream, such as {@code mp4}, or {@code none} for an audio-only stream. */
  public @Nullable String video_ext;

  /** The average video bitrate in kilobits per second, or zero if unknown. */
  public double vbr;

  /** The average audio bitrate in kilobits per second, or zero if unknown. */
  public double abr;

  /** The average total bitrate of audio and video in kilobits per second, or zero if unknown. */
  public double tbr;

  /** A readable description of the stream, such as {@code 251 - audio only (medium)}. */
  public @Nullable String format;

  /** The position of the stream in its HLS or DASH manifest, or null if it was not listed in a manifest. */
  public @Nullable Object format_index;

  /** The URL of the HLS or DASH manifest that listed the stream, or null if it was not listed in a manifest. */
  public @Nullable String manifest_url;

  /** The language code of the audio, such as {@code en}, or null if unknown. */
  public @Nullable String language;

  /** How strongly yt-dlp prefers the stream over the other streams of the site, or null for no preference. */
  public @Nullable Object preference;

  /** The quality rank yt-dlp gives the stream among the streams of the video, where higher is better. */
  public double quality;

  /** Whether the stream is protected by DRM, which players cannot decode. */
  public boolean has_drm;

  /** How strongly yt-dlp prefers the source of the stream, where higher is better. */
  public int source_preference;

  /** The audio sample rate in hertz, or zero for a stream without audio. */
  public int asr;

  /** The exact size of the stream in bytes, or zero if unknown. */
  public long filesize;

  /** The number of audio channels, or zero for a stream without audio. */
  public int audio_channels;

  /** How strongly yt-dlp prefers the language of the stream, where higher is better. */
  public int language_preference;

  /** The dynamic range of the video, such as {@code SDR} or {@code HDR10}, or null for a stream without video. */
  public @Nullable String dynamic_range;

  /** The container of the stream, such as {@code mp4_dash} or {@code webm_dash}, or null if unknown. */
  public @Nullable String container;

  /** Hints for downloading the stream, such as the size of the chunks to request, or null if there are none. */
  public @Nullable DownloaderOptions downloader_options;

  /**
   * Gets the URL of this stream as a source that can be played.
   *
   * @return the stream URL as a source
   * @throws IllegalStateException if yt-dlp did not report a URL for this stream
   */
  public UriSource toUriSource() {
    final String streamUrl = this.url;
    if (streamUrl == null) {
      throw new IllegalStateException("Stream has no URL");
    }
    final URI uri = URI.create(streamUrl);
    return UriSource.uri(uri);
  }
}
