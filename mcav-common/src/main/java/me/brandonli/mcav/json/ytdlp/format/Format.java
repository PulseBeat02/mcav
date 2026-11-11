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
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stream of a video as listed by yt-dlp, such as a 1080p MP4 video stream or an Opus audio stream.
 *
 * <p>Gson fills instances from the JSON yt-dlp prints. Values yt-dlp did not report are empty, zero or false; a
 * primitive zero or false does not tell a missing value from a reported one.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
@JsonAdapter(Format.Deserializer.class)
public final class Format {

  @SerializedName("format_id")
  private final @Nullable String formatId;

  @SerializedName("format_note")
  private final @Nullable String formatNote;

  @SerializedName("ext")
  private final @Nullable String extension;

  private final @Nullable String protocol;

  @SerializedName("acodec")
  private final @Nullable String audioCodec;

  @SerializedName("vcodec")
  private final @Nullable String videoCodec;

  private final @Nullable String url;

  private final int width;

  private final int height;

  @SerializedName("fps")
  private final double framesPerSecond;

  private final int rows;

  private final int columns;

  private final @Nullable List<Fragment> fragments;

  private final @Nullable String resolution;

  @SerializedName("aspect_ratio")
  private final double aspectRatio;

  @SerializedName("filesize_approx")
  private final long approximateFileSize;

  @SerializedName("http_headers")
  private final @Nullable HttpHeaders httpHeaders;

  @SerializedName("audio_ext")
  private final @Nullable String audioExtension;

  @SerializedName("video_ext")
  private final @Nullable String videoExtension;

  @SerializedName("vbr")
  private final double videoBitrate;

  @SerializedName("abr")
  private final double audioBitrate;

  @SerializedName("tbr")
  private final double totalBitrate;

  private final @Nullable String format;

  @SerializedName("format_index")
  private final @Nullable Object formatIndex;

  @SerializedName("manifest_url")
  private final @Nullable String manifestUrl;

  private final @Nullable String language;

  private final @Nullable Object preference;

  private final double quality;

  @SerializedName("has_drm")
  private final boolean drmProtected;

  @SerializedName("source_preference")
  private final int sourcePreference;

  @SerializedName("asr")
  private final int audioSampleRate;

  @SerializedName("filesize")
  private final long fileSize;

  @SerializedName("audio_channels")
  private final int audioChannels;

  @SerializedName("language_preference")
  private final int languagePreference;

  @SerializedName("dynamic_range")
  private final @Nullable String dynamicRange;

  private final @Nullable String container;

  @SerializedName("downloader_options")
  private final @Nullable DownloaderOptions downloaderOptions;

  private Format(final Fields fields) {
    this.formatId = fields.formatId();
    this.formatNote = fields.formatNote();
    this.extension = fields.extension();
    this.protocol = fields.protocol();
    this.audioCodec = fields.audioCodec();
    this.videoCodec = fields.videoCodec();
    this.url = fields.url();
    this.width = Objects.requireNonNullElse(fields.width(), 0);
    this.height = Objects.requireNonNullElse(fields.height(), 0);
    this.framesPerSecond = Objects.requireNonNullElse(fields.framesPerSecond(), 0.0);
    this.rows = Objects.requireNonNullElse(fields.rows(), 0);
    this.columns = Objects.requireNonNullElse(fields.columns(), 0);
    this.fragments = JsonValueUtils.withoutNulls(fields.fragments());
    this.resolution = fields.resolution();
    this.aspectRatio = Objects.requireNonNullElse(fields.aspectRatio(), 0.0);
    this.approximateFileSize = Objects.requireNonNullElse(fields.approximateFileSize(), 0L);
    this.httpHeaders = fields.httpHeaders();
    this.audioExtension = fields.audioExtension();
    this.videoExtension = fields.videoExtension();
    this.videoBitrate = Objects.requireNonNullElse(fields.videoBitrate(), 0.0);
    this.audioBitrate = Objects.requireNonNullElse(fields.audioBitrate(), 0.0);
    this.totalBitrate = Objects.requireNonNullElse(fields.totalBitrate(), 0.0);
    this.format = fields.format();
    this.formatIndex = JsonValueUtils.toUnmodifiable(fields.formatIndex());
    this.manifestUrl = fields.manifestUrl();
    this.language = fields.language();
    this.preference = JsonValueUtils.toUnmodifiable(fields.preference());
    this.quality = Objects.requireNonNullElse(fields.quality(), 0.0);
    this.drmProtected = Objects.requireNonNullElse(fields.drmProtected(), false);
    this.sourcePreference = Objects.requireNonNullElse(fields.sourcePreference(), 0);
    this.audioSampleRate = Objects.requireNonNullElse(fields.audioSampleRate(), 0);
    this.fileSize = Objects.requireNonNullElse(fields.fileSize(), 0L);
    this.audioChannels = Objects.requireNonNullElse(fields.audioChannels(), 0);
    this.languagePreference = Objects.requireNonNullElse(fields.languagePreference(), 0);
    this.dynamicRange = fields.dynamicRange();
    this.container = fields.container();
    this.downloaderOptions = fields.downloaderOptions();
  }

  /**
   * Wraps the reported URL in a source. This does not open or validate the remote stream and does not transfer
   * {@link #getHttpHeaders()}; a caller must separately configure any headers required by its playback backend.
   *
   * @return the stream URL as a source
   * @throws IllegalStateException    if yt-dlp did not report a URL for this stream
   * @throws IllegalArgumentException if the reported URL is not a syntactically valid URI
   * @since 1.0.0
   */
  public UriSource toUriSource() {
    final String streamUrl = this.url;
    if (streamUrl == null) {
      final String message = "Stream has no URL!";
      throw new IllegalStateException(message);
    }

    final URI uri = URI.create(streamUrl);
    return UriSource.uri(uri);
  }

  /**
   * Returns the identifier of the stream on its site, such as {@code 251}, which yt-dlp accepts in its format
   * option, or empty if yt-dlp did not report it.
   *
   * @return the identifier of the stream on its site, such as {@code 251}, which yt-dlp accepts in its format
   * option, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getFormatId() {
    return Optional.ofNullable(this.formatId);
  }

  /**
   * Returns a short readable note about the stream, such as {@code 1080p} or {@code medium}, or empty if yt-dlp did
   * not report it.
   *
   * @return a short readable note about the stream, such as {@code 1080p} or {@code medium}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getFormatNote() {
    return Optional.ofNullable(this.formatNote);
  }

  /**
   * Returns the file extension of the stream, such as {@code mp4} or {@code webm}, or empty if yt-dlp did not
   * report it.
   *
   * @return the file extension of the stream, such as {@code mp4} or {@code webm}, or empty if yt-dlp did not
   * report it
   * @since 1.0.0
   */
  public Optional<String> getExtension() {
    return Optional.ofNullable(this.extension);
  }

  /**
   * Returns the protocol of the stream: {@code https}, {@code m3u8_native} for HLS, or {@code http_dash_segments}
   * for DASH, or empty if yt-dlp did not report it.
   *
   * @return the protocol of the stream: {@code https}, {@code m3u8_native} for HLS, or {@code http_dash_segments}
   * for DASH, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getProtocol() {
    return Optional.ofNullable(this.protocol);
  }

  /**
   * Returns the audio codec, such as {@code opus}, or {@code none} for a stream without audio, or empty if yt-dlp
   * did not report it.
   *
   * @return the audio codec, such as {@code opus}, or {@code none} for a stream without audio, or empty if yt-dlp
   * did not report it
   * @since 1.0.0
   */
  public Optional<String> getAudioCodec() {
    return Optional.ofNullable(this.audioCodec);
  }

  /**
   * Returns the video codec, such as {@code avc1.640028}, or {@code none} for a stream without video, or empty if
   * yt-dlp did not report it.
   *
   * @return the video codec, such as {@code avc1.640028}, or {@code none} for a stream without video, or empty if
   * yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getVideoCodec() {
    return Optional.ofNullable(this.videoCodec);
  }

  /**
   * Returns the direct URL of the stream, which a player opens with the {@link #getHttpHeaders()}, or empty if
   * yt-dlp did not report it.
   *
   * @return the direct URL of the stream, which a player opens with the {@link #getHttpHeaders()}, or empty if
   * yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getUrl() {
    return Optional.ofNullable(this.url);
  }

  /**
   * Returns the width of the video in pixels, or zero for a stream without video.
   *
   * @return the width of the video in pixels, or zero for a stream without video
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Returns the height of the video in pixels, or zero for a stream without video.
   *
   * @return the height of the video in pixels, or zero for a stream without video
   * @since 1.0.0
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Returns the frame rate of the video in frames per second, or zero for a stream without video.
   *
   * @return the frame rate of the video in frames per second, or zero for a stream without video
   * @since 1.0.0
   */
  public double getFramesPerSecond() {
    return this.framesPerSecond;
  }

  /**
   * Returns the number of thumbnail rows in each image of a storyboard stream, or zero for other streams.
   *
   * @return the number of thumbnail rows in each image of a storyboard stream, or zero for other streams
   * @since 1.0.0
   */
  public int getRows() {
    return this.rows;
  }

  /**
   * Returns the number of thumbnail columns in each image of a storyboard stream, or zero for other streams.
   *
   * @return the number of thumbnail columns in each image of a storyboard stream, or zero for other streams
   * @since 1.0.0
   */
  public int getColumns() {
    return this.columns;
  }

  /**
   * Returns the fragments of a segmented stream, such as the images of a storyboard, or an empty list for a stream
   * served whole.
   *
   * @return the fragments of a segmented stream, such as the images of a storyboard, or an empty list for a stream
   * served whole
   * @since 1.0.0
   */
  public List<Fragment> getFragments() {
    if (this.fragments == null) {
      return List.of();
    }

    return Collections.unmodifiableList(this.fragments);
  }

  /**
   * Returns the resolution as text, such as {@code 1920x1080}, or {@code audio only} for a stream without video, or
   * empty if yt-dlp did not report it.
   *
   * @return the resolution as text, such as {@code 1920x1080}, or {@code audio only} for a stream without video, or
   * empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getResolution() {
    return Optional.ofNullable(this.resolution);
  }

  /**
   * Returns the width of the video divided by its height, or zero for a stream without video.
   *
   * @return the width of the video divided by its height, or zero for a stream without video
   * @since 1.0.0
   */
  public double getAspectRatio() {
    return this.aspectRatio;
  }

  /**
   * Returns the size of the stream in bytes as estimated from its bitrate and duration, or zero if unknown.
   *
   * @return the size of the stream in bytes as estimated from its bitrate and duration, or zero if unknown
   * @since 1.0.0
   */
  public long getApproximateFileSize() {
    return this.approximateFileSize;
  }

  /**
   * Returns the HTTP headers that requests for the stream must send, or empty if no headers were reported.
   *
   * @return the HTTP headers that requests for the stream must send, or empty if no headers were reported
   * @since 1.0.0
   */
  public Optional<HttpHeaders> getHttpHeaders() {
    return Optional.ofNullable(this.httpHeaders);
  }

  /**
   * Returns the audio extension of an audio-only stream, such as {@code m4a}, or {@code none} for a stream with
   * video, or empty if yt-dlp did not report it.
   *
   * @return the audio extension of an audio-only stream, such as {@code m4a}, or {@code none} for a stream with
   * video, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getAudioExtension() {
    return Optional.ofNullable(this.audioExtension);
  }

  /**
   * Returns the video extension of the stream, such as {@code mp4}, or {@code none} for an audio-only stream, or
   * empty if yt-dlp did not report it.
   *
   * @return the video extension of the stream, such as {@code mp4}, or {@code none} for an audio-only stream, or
   * empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getVideoExtension() {
    return Optional.ofNullable(this.videoExtension);
  }

  /**
   * Returns the average video bitrate in kilobits per second, or zero if unknown.
   *
   * @return the average video bitrate in kilobits per second, or zero if unknown
   * @since 1.0.0
   */
  public double getVideoBitrate() {
    return this.videoBitrate;
  }

  /**
   * Returns the average audio bitrate in kilobits per second, or zero if unknown.
   *
   * @return the average audio bitrate in kilobits per second, or zero if unknown
   * @since 1.0.0
   */
  public double getAudioBitrate() {
    return this.audioBitrate;
  }

  /**
   * Returns the average total bitrate of audio and video in kilobits per second, or zero if unknown.
   *
   * @return the average total bitrate of audio and video in kilobits per second, or zero if unknown
   * @since 1.0.0
   */
  public double getTotalBitrate() {
    return this.totalBitrate;
  }

  /**
   * Returns a readable description of the stream, such as {@code 251 - audio only (medium)}, or empty if yt-dlp did
   * not report it.
   *
   * @return a readable description of the stream, such as {@code 251 - audio only (medium)}, or empty if yt-dlp did
   * not report it
   * @since 1.0.0
   */
  public Optional<String> getFormat() {
    return Optional.ofNullable(this.format);
  }

  /**
   * Returns the position of the stream in its HLS or DASH manifest, or empty if it was not listed in a manifest.
   *
   * @return the position of the stream in its HLS or DASH manifest, or empty if it was not listed in a manifest
   * @since 1.0.0
   */
  public Optional<Object> getFormatIndex() {
    return Optional.ofNullable(this.formatIndex);
  }

  /**
   * Returns the URL of the HLS or DASH manifest that listed the stream, or empty if it was not listed in a
   * manifest.
   *
   * @return the URL of the HLS or DASH manifest that listed the stream, or empty if it was not listed in a manifest
   * @since 1.0.0
   */
  public Optional<String> getManifestUrl() {
    return Optional.ofNullable(this.manifestUrl);
  }

  /**
   * Returns the language code of the audio, such as {@code en}, or empty if unknown.
   *
   * @return the language code of the audio, such as {@code en}, or empty if unknown
   * @since 1.0.0
   */
  public Optional<String> getLanguage() {
    return Optional.ofNullable(this.language);
  }

  /**
   * Returns how strongly yt-dlp prefers the stream over the other streams of the site, or empty for no preference.
   *
   * @return how strongly yt-dlp prefers the stream over the other streams of the site, or empty for no preference
   * @since 1.0.0
   */
  public Optional<Object> getPreference() {
    return Optional.ofNullable(this.preference);
  }

  /**
   * Returns the quality rank yt-dlp gives the stream among the streams of the video, where higher is better.
   *
   * @return the quality rank yt-dlp gives the stream among the streams of the video, where higher is better
   * @since 1.0.0
   */
  public double getQuality() {
    return this.quality;
  }

  /**
   * Returns whether the stream is protected by DRM, which players cannot decode.
   *
   * @return whether the stream is protected by DRM, which players cannot decode
   * @since 1.0.0
   */
  public boolean isDrmProtected() {
    return this.drmProtected;
  }

  /**
   * Returns how strongly yt-dlp prefers the source of the stream, where higher is better.
   *
   * @return how strongly yt-dlp prefers the source of the stream, where higher is better
   * @since 1.0.0
   */
  public int getSourcePreference() {
    return this.sourcePreference;
  }

  /**
   * Returns the audio sample rate in hertz, or zero for a stream without audio.
   *
   * @return the audio sample rate in hertz, or zero for a stream without audio
   * @since 1.0.0
   */
  public int getAudioSampleRate() {
    return this.audioSampleRate;
  }

  /**
   * Returns the exact size of the stream in bytes, or zero if unknown.
   *
   * @return the exact size of the stream in bytes, or zero if unknown
   * @since 1.0.0
   */
  public long getFileSize() {
    return this.fileSize;
  }

  /**
   * Returns the number of audio channels, or zero for a stream without audio.
   *
   * @return the number of audio channels, or zero for a stream without audio
   * @since 1.0.0
   */
  public int getAudioChannels() {
    return this.audioChannels;
  }

  /**
   * Returns how strongly yt-dlp prefers the language of the stream, where higher is better.
   *
   * @return how strongly yt-dlp prefers the language of the stream, where higher is better
   * @since 1.0.0
   */
  public int getLanguagePreference() {
    return this.languagePreference;
  }

  /**
   * Returns the dynamic range of the video, such as {@code SDR} or {@code HDR10}, or empty for a stream without
   * video.
   *
   * @return the dynamic range of the video, such as {@code SDR} or {@code HDR10}, or empty for a stream without
   * video
   * @since 1.0.0
   */
  public Optional<String> getDynamicRange() {
    return Optional.ofNullable(this.dynamicRange);
  }

  /**
   * Returns the container of the stream, such as {@code mp4_dash} or {@code webm_dash}, or empty if unknown.
   *
   * @return the container of the stream, such as {@code mp4_dash} or {@code webm_dash}, or empty if unknown
   * @since 1.0.0
   */
  public Optional<String> getContainer() {
    return Optional.ofNullable(this.container);
  }

  /**
   * Returns hints for downloading the stream, such as the size of the chunks to request, or empty if there are
   * none.
   *
   * @return hints for downloading the stream, such as the size of the chunks to request, or empty if there are none
   * @since 1.0.0
   */
  public Optional<DownloaderOptions> getDownloaderOptions() {
    return Optional.ofNullable(this.downloaderOptions);
  }

  /**
   * Returns whether {@code other} is a format with the same values.
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

    if (!(other instanceof final Format format)) {
      return false;
    }

    return (
      Objects.equals(this.formatId, format.formatId) &&
      Objects.equals(this.formatNote, format.formatNote) &&
      Objects.equals(this.extension, format.extension) &&
      Objects.equals(this.protocol, format.protocol) &&
      Objects.equals(this.audioCodec, format.audioCodec) &&
      Objects.equals(this.videoCodec, format.videoCodec) &&
      Objects.equals(this.url, format.url) &&
      this.width == format.width &&
      this.height == format.height &&
      Double.compare(this.framesPerSecond, format.framesPerSecond) == 0 &&
      this.rows == format.rows &&
      this.columns == format.columns &&
      Objects.equals(this.fragments, format.fragments) &&
      Objects.equals(this.resolution, format.resolution) &&
      Double.compare(this.aspectRatio, format.aspectRatio) == 0 &&
      this.approximateFileSize == format.approximateFileSize &&
      Objects.equals(this.httpHeaders, format.httpHeaders) &&
      Objects.equals(this.audioExtension, format.audioExtension) &&
      Objects.equals(this.videoExtension, format.videoExtension) &&
      Double.compare(this.videoBitrate, format.videoBitrate) == 0 &&
      Double.compare(this.audioBitrate, format.audioBitrate) == 0 &&
      Double.compare(this.totalBitrate, format.totalBitrate) == 0 &&
      Objects.equals(this.format, format.format) &&
      Objects.equals(this.formatIndex, format.formatIndex) &&
      Objects.equals(this.manifestUrl, format.manifestUrl) &&
      Objects.equals(this.language, format.language) &&
      Objects.equals(this.preference, format.preference) &&
      Double.compare(this.quality, format.quality) == 0 &&
      this.drmProtected == format.drmProtected &&
      this.sourcePreference == format.sourcePreference &&
      this.audioSampleRate == format.audioSampleRate &&
      this.fileSize == format.fileSize &&
      this.audioChannels == format.audioChannels &&
      this.languagePreference == format.languagePreference &&
      Objects.equals(this.dynamicRange, format.dynamicRange) &&
      Objects.equals(this.container, format.container) &&
      Objects.equals(this.downloaderOptions, format.downloaderOptions)
    );
  }

  /**
   * Returns a hash code built from every field of this format.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(
      this.formatId,
      this.formatNote,
      this.extension,
      this.protocol,
      this.audioCodec,
      this.videoCodec,
      this.url,
      this.width,
      this.height,
      this.framesPerSecond,
      this.rows,
      this.columns,
      this.fragments,
      this.resolution,
      this.aspectRatio,
      this.approximateFileSize,
      this.httpHeaders,
      this.audioExtension,
      this.videoExtension,
      this.videoBitrate,
      this.audioBitrate,
      this.totalBitrate,
      this.format,
      this.formatIndex,
      this.manifestUrl,
      this.language,
      this.preference,
      this.quality,
      this.drmProtected,
      this.sourcePreference,
      this.audioSampleRate,
      this.fileSize,
      this.audioChannels,
      this.languagePreference,
      this.dynamicRange,
      this.container,
      this.downloaderOptions
    );
  }

  /**
   * Returns a debugging representation with every field of this format.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("formatId", this.formatId)
      .add("formatNote", this.formatNote)
      .add("extension", this.extension)
      .add("protocol", this.protocol)
      .add("audioCodec", this.audioCodec)
      .add("videoCodec", this.videoCodec)
      .add("url", this.url)
      .add("width", this.width)
      .add("height", this.height)
      .add("framesPerSecond", this.framesPerSecond)
      .add("rows", this.rows)
      .add("columns", this.columns)
      .add("fragments", this.fragments)
      .add("resolution", this.resolution)
      .add("aspectRatio", this.aspectRatio)
      .add("approximateFileSize", this.approximateFileSize)
      .add("httpHeaders", this.httpHeaders)
      .add("audioExtension", this.audioExtension)
      .add("videoExtension", this.videoExtension)
      .add("videoBitrate", this.videoBitrate)
      .add("audioBitrate", this.audioBitrate)
      .add("totalBitrate", this.totalBitrate)
      .add("format", this.format)
      .add("formatIndex", this.formatIndex)
      .add("manifestUrl", this.manifestUrl)
      .add("language", this.language)
      .add("preference", this.preference)
      .add("quality", this.quality)
      .add("drmProtected", this.drmProtected)
      .add("sourcePreference", this.sourcePreference)
      .add("audioSampleRate", this.audioSampleRate)
      .add("fileSize", this.fileSize)
      .add("audioChannels", this.audioChannels)
      .add("languagePreference", this.languagePreference)
      .add("dynamicRange", this.dynamicRange)
      .add("container", this.container)
      .add("downloaderOptions", this.downloaderOptions)
      .toString();
  }

  static final class Deserializer implements JsonDeserializer<Format> {

    @Override
    public Format deserialize(final JsonElement json, final Type type, final JsonDeserializationContext context) {
      final @Nullable Fields parsed = context.deserialize(json, Fields.class);
      final Fields fields = requireNonNull(parsed); // checker framework
      return new Format(fields);
    }
  }

  private record Fields(
    @SerializedName("format_id") @Nullable String formatId,
    @SerializedName("format_note") @Nullable String formatNote,
    @SerializedName("ext") @Nullable String extension,
    @Nullable String protocol,
    @SerializedName("acodec") @Nullable String audioCodec,
    @SerializedName("vcodec") @Nullable String videoCodec,
    @Nullable String url,
    @Nullable Integer width,
    @Nullable Integer height,
    @SerializedName("fps") @Nullable Double framesPerSecond,
    @Nullable Integer rows,
    @Nullable Integer columns,
    @Nullable List<Fragment> fragments,
    @Nullable String resolution,
    @SerializedName("aspect_ratio") @Nullable Double aspectRatio,
    @SerializedName("filesize_approx") @Nullable Long approximateFileSize,
    @SerializedName("http_headers") @Nullable HttpHeaders httpHeaders,
    @SerializedName("audio_ext") @Nullable String audioExtension,
    @SerializedName("video_ext") @Nullable String videoExtension,
    @SerializedName("vbr") @Nullable Double videoBitrate,
    @SerializedName("abr") @Nullable Double audioBitrate,
    @SerializedName("tbr") @Nullable Double totalBitrate,
    @Nullable String format,
    @SerializedName("format_index") @Nullable Object formatIndex,
    @SerializedName("manifest_url") @Nullable String manifestUrl,
    @Nullable String language,
    @Nullable Object preference,
    @Nullable Double quality,
    @SerializedName("has_drm") @Nullable Boolean drmProtected,
    @SerializedName("source_preference") @Nullable Integer sourcePreference,
    @SerializedName("asr") @Nullable Integer audioSampleRate,
    @SerializedName("filesize") @Nullable Long fileSize,
    @SerializedName("audio_channels") @Nullable Integer audioChannels,
    @SerializedName("language_preference") @Nullable Integer languagePreference,
    @SerializedName("dynamic_range") @Nullable String dynamicRange,
    @Nullable String container,
    @SerializedName("downloader_options") @Nullable DownloaderOptions downloaderOptions
  ) {}
}
