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
package me.brandonli.mcav.json.ytdlp.strategy;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Picks one stream out of the streams yt-dlp found for a page.
 *
 * <p>yt-dlp lists many streams per video, typically separate audio and video streams in several qualities and
 * protocols. A strategy encodes a preference, such as the best quality audio or an MP4 video over plain HTTPS,
 * which players decode most reliably. Strategies never fail on missing metadata: streams without the needed
 * information are simply skipped.
 *
 * <p>yt-dlp lists the streams from worst to best. The quality strategies therefore break ties in that order: among
 * streams of equal quality, the best quality strategies pick the stream listed last and the lowest quality
 * strategies pick the stream listed first.
 */
@FunctionalInterface
public interface FormatStrategy {
  /**
   * The lowest quality audio-only stream.
   */
  FormatStrategy LOWEST_QUALITY_AUDIO = dump ->
    pick(dump, FormatStrategy::hasAudio, (candidate, current) -> candidate.quality < current.quality);

  /**
   * The lowest quality stream that contains video.
   */
  FormatStrategy LOWEST_QUALITY_VIDEO = dump ->
    pick(dump, FormatStrategy::hasVideo, (candidate, current) -> candidate.quality < current.quality);

  /**
   * The highest quality audio-only stream.
   */
  FormatStrategy BEST_QUALITY_AUDIO = dump ->
    pick(dump, FormatStrategy::hasAudio, (candidate, current) -> candidate.quality >= current.quality);

  /**
   * The highest quality stream that contains video.
   */
  FormatStrategy BEST_QUALITY_VIDEO = dump ->
    pick(dump, FormatStrategy::hasVideo, (candidate, current) -> candidate.quality >= current.quality);

  /**
   * The first audio-only stream, in the order yt-dlp listed them.
   */
  FormatStrategy FIRST_AUDIO = dump -> first(dump, FormatStrategy::hasAudio);

  /**
   * The first stream that contains video, in the order yt-dlp listed them.
   */
  FormatStrategy FIRST_VIDEO = dump -> first(dump, FormatStrategy::hasVideo);

  /**
   * The first WebM audio stream served over plain HTTPS, which players decode without extra protocols.
   */
  FormatStrategy PREFER_WEBM_AUDIO = dump -> first(dump, format -> isHttps(format) && "webm".equals(format.audio_ext));

  /**
   * The first MP4 video stream served over plain HTTPS, which players decode without extra protocols.
   */
  FormatStrategy PREFER_MP4_VIDEO = dump -> first(dump, format -> isHttps(format) && "mp4".equals(format.video_ext));

  /**
   * Selects a stream.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected stream, or empty if no stream matches
   */
  Optional<Format> select(final URLParseDump dump);

  /**
   * Checks whether a stream is an audio-only stream. yt-dlp reports an audio extension only for streams without
   * video, so streams that combine audio and video are not audio streams in this sense; this keeps the audio
   * strategies from downloading video data.
   *
   * @param format the stream
   * @return true if the stream has an audio track and no video track
   */
  static boolean hasAudio(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null");
    final String audioExtension = format.audio_ext;
    return audioExtension != null && !audioExtension.equals("none");
  }

  /**
   * Checks whether a stream contains video, with or without audio.
   *
   * @param format the stream
   * @return true if the stream has a video track
   */
  static boolean hasVideo(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null");
    final String videoExtension = format.video_ext;
    return videoExtension != null && !videoExtension.equals("none");
  }

  /**
   * Checks whether a stream is served over plain HTTPS rather than a streaming protocol such as HLS or DASH.
   *
   * @param format the stream
   * @return true if the stream URL uses HTTPS
   */
  static boolean isHttps(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null");
    return "https".equals(format.protocol);
  }

  private static List<Format> formats(final URLParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null");
    final List<Format> formats = dump.formats;
    if (formats == null) {
      return List.of();
    }
    return formats;
  }

  private static Optional<Format> first(final URLParseDump dump, final Predicate<Format> filter) {
    final List<Format> formats = formats(dump);
    for (final Format format : formats) {
      if (format != null && filter.test(format)) {
        return Optional.of(format);
      }
    }
    return Optional.empty();
  }

  /**
   * Picks one of the matching streams in list order.
   *
   * @param replaces decides whether a later candidate replaces the stream picked so far
   */
  private static Optional<Format> pick(
    final URLParseDump dump,
    final Predicate<Format> filter,
    final BiPredicate<Format, Format> replaces
  ) {
    final List<Format> formats = formats(dump);
    @Nullable Format picked = null;
    for (final Format format : formats) {
      if (format == null || !filter.test(format)) {
        continue;
      }
      if (picked == null || replaces.test(format, picked)) {
        picked = format;
      }
    }
    return Optional.ofNullable(picked);
  }
}
