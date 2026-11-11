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
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Picks one stream out of the streams yt-dlp found for a page.
 *
 * <p>yt-dlp lists many streams per video, typically separate audio and video streams in several qualities and
 * protocols. A strategy encodes a preference, such as the best quality audio or an MP4 video over plain HTTPS,
 * which players decode most reliably. Built-in strategies skip entries missing the extension
 * or protocol marker they require. A missing format list selects nothing. Quality strategies compare the
 * numeric {@link Format#getQuality()} value, including its default zero value; they do not calculate a bitrate or
 * resolution score. Built-in strategies are stateless, and the metadata they read is immutable. Selected formats
 * are borrowed objects from the dump, not copies.
 *
 * <p>yt-dlp lists the streams from worst to best. The quality strategies therefore break ties in that order: among
 * streams of equal quality, the best quality strategies pick the stream listed last and the lowest quality
 * strategies pick the stream listed first.
 *
 * <p>Implementations in this library are immutable and thread-safe.
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface FormatStrategy {
  /**
   * The lowest quality audio-only stream.
   *
   * @since 1.0.0
   */
  FormatStrategy LOWEST_QUALITY_AUDIO = dump ->
    pick(dump, FormatStrategy::hasAudio, (candidate, current) -> candidate.getQuality() < current.getQuality());

  /**
   * The lowest quality stream that contains video.
   *
   * @since 1.0.0
   */
  FormatStrategy LOWEST_QUALITY_VIDEO = dump ->
    pick(dump, FormatStrategy::hasVideo, (candidate, current) -> candidate.getQuality() < current.getQuality());

  /**
   * The highest quality audio-only stream.
   *
   * @since 1.0.0
   */
  FormatStrategy BEST_QUALITY_AUDIO = dump ->
    pick(dump, FormatStrategy::hasAudio, (candidate, current) -> candidate.getQuality() >= current.getQuality());

  /**
   * The highest quality stream that contains video.
   *
   * @since 1.0.0
   */
  FormatStrategy BEST_QUALITY_VIDEO = dump ->
    pick(dump, FormatStrategy::hasVideo, (candidate, current) -> candidate.getQuality() >= current.getQuality());

  /**
   * The first audio-only stream, in the order yt-dlp listed them.
   *
   * @since 1.0.0
   */
  FormatStrategy FIRST_AUDIO = dump -> first(dump, FormatStrategy::hasAudio);

  /**
   * The first stream that contains video, in the order yt-dlp listed them.
   *
   * @since 1.0.0
   */
  FormatStrategy FIRST_VIDEO = dump -> first(dump, FormatStrategy::hasVideo);

  /**
   * The first WebM audio stream served over plain HTTPS, which players decode without extra protocols.
   *
   * @since 1.0.0
   */
  FormatStrategy PREFER_WEBM_AUDIO = dump -> first(dump, format -> isHttps(format) && hasAudioExtension(format, "webm"));

  /**
   * The first MP4 video stream served over plain HTTPS, which players decode without extra protocols.
   *
   * @since 1.0.0
   */
  FormatStrategy PREFER_MP4_VIDEO = dump -> first(dump, format -> isHttps(format) && hasVideoExtension(format, "mp4"));

  /**
   * Selects a stream.
   *
   * @param dump the metadata yt-dlp produced
   * @return the selected stream, or empty if no stream matches
   * @throws NullPointerException if {@code dump} is null when using a built-in strategy
   * @since 1.0.0
   */
  Optional<Format> select(final UrlParseDump dump);

  /**
   * Classifies a stream by its audio-extension marker. A reported {@link Format#getAudioExtension()} other than
   * {@code none} is accepted, as used for audio-only formats in yt-dlp metadata. This does not independently
   * inspect the video extension, codecs or URL.
   *
   * @param format the non-null stream metadata
   * @return true if its audio-extension marker is present and not {@code none}
   * @throws NullPointerException if {@code format} is null
   * @since 1.0.0
   */
  static boolean hasAudio(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null!");

    final Optional<String> audioExtension = format.getAudioExtension();
    final Optional<String> reported = audioExtension.filter(FormatStrategy::isReported);
    return reported.isPresent();
  }

  /**
   * Returns whether a stream contains video, with or without audio.
   *
   * @param format the stream
   * @return true if {@link Format#getVideoExtension()} is present and not {@code none}; no stream is opened or inspected
   * @throws NullPointerException if {@code format} is null
   * @since 1.0.0
   */
  static boolean hasVideo(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null!");

    final Optional<String> videoExtension = format.getVideoExtension();
    final Optional<String> reported = videoExtension.filter(FormatStrategy::isReported);
    return reported.isPresent();
  }

  /**
   * Returns whether a stream is served over plain HTTPS rather than a streaming protocol such as HLS or DASH.
   *
   * @param format the stream
   * @return true if {@link Format#getProtocol()} equals {@code https}, case-sensitively; the URL is not parsed
   * @throws NullPointerException if {@code format} is null
   * @since 1.0.0
   */
  static boolean isHttps(final Format format) {
    Preconditions.checkNotNull(format, "Format must not be null!");

    final Optional<String> protocol = format.getProtocol();
    final Optional<String> https = protocol.filter("https"::equals);
    return https.isPresent();
  }

  private static List<Format> formats(final UrlParseDump dump) {
    Preconditions.checkNotNull(dump, "Dump must not be null!");

    return dump.getFormats();
  }

  private static boolean isReported(final String extension) {
    return !extension.equals("none");
  }

  private static boolean hasAudioExtension(final Format format, final String extension) {
    final Optional<String> audioExtension = format.getAudioExtension();
    final Optional<String> matching = audioExtension.filter(extension::equals);
    return matching.isPresent();
  }

  private static boolean hasVideoExtension(final Format format, final String extension) {
    final Optional<String> videoExtension = format.getVideoExtension();
    final Optional<String> matching = videoExtension.filter(extension::equals);
    return matching.isPresent();
  }

  private static Optional<Format> first(final UrlParseDump dump, final Predicate<Format> filter) {
    final List<Format> formats = formats(dump);
    for (final Format format : formats) {
      if (filter.test(format)) {
        return Optional.of(format);
      }
    }

    return Optional.empty();
  }

  private static Optional<Format> pick(
    final UrlParseDump dump,
    final Predicate<Format> filter,
    final BiPredicate<Format, Format> replaces
  ) {
    final List<Format> formats = formats(dump);
    @Nullable Format picked = null;
    for (final Format format : formats) {
      if (!filter.test(format)) {
        continue;
      }
      if (picked == null || replaces.test(format, picked)) {
        picked = format;
      }
    }

    return Optional.ofNullable(picked);
  }
}
