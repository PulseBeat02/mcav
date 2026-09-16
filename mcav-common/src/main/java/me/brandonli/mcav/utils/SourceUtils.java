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
package me.brandonli.mcav.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import me.brandonli.mcav.json.ytdlp.YTDLPParseException;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Helpers for classifying strings and sources: whether a string is a path or a URL, whether a URL points at a
 * media file, and whether a source is an animated GIF.
 */
public final class SourceUtils {

  private static final Set<String> MEDIA_EXTENSIONS = Set.of(
    // video containers
    "mp4",
    "m4v",
    "mkv",
    "webm",
    "mov",
    "avi",
    "wmv",
    "asf",
    "flv",
    "f4v",
    "ts",
    "mts",
    "m2ts",
    "mpg",
    "mpeg",
    "m2v",
    "vob",
    "mxf",
    "ogv",
    "3gp",
    "3g2",
    // streaming manifests
    "m3u8",
    "mpd",
    // audio
    "ogg",
    "oga",
    "mka",
    "weba",
    "mp3",
    "m4a",
    "aac",
    "ac3",
    "wav",
    "aif",
    "aiff",
    "flac",
    "opus",
    "wma",
    "amr",
    // images
    "gif",
    "png",
    "apng",
    "jpg",
    "jpeg",
    "bmp",
    "webp",
    "avif",
    "tif",
    "tiff"
  );

  private SourceUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether a source points at a GIF, judging by the file extension of its path or URL.
   *
   * @param source the source
   * @return true if the path or URL ends with {@code .gif}
   */
  public static boolean isImageGif(final Source source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final String resource;
    if (source instanceof final FileSource fileSource) {
      final Path path = fileSource.getPath();
      resource = path.toString();
    } else if (source instanceof final UriSource uriSource) {
      final URI uri = uriSource.getUri();
      resource = uri.getPath();
    } else {
      return false;
    }
    if (resource == null) {
      return false;
    }
    final String extension = getExtension(resource);
    return "gif".equals(extension);
  }

  /**
   * Checks whether a string is a URL with both a scheme and a host, such as {@code https://example.com/a.mp4}.
   *
   * @param raw the string
   * @return true if the string is such a URL
   */
  public static boolean isUri(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    try {
      final URI uri = URI.create(raw);
      final String scheme = uri.getScheme();
      final String host = uri.getHost();
      return scheme != null && host != null;
    } catch (final IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Checks whether a URL points directly at a media file, judging by a well-known media extension at the end of
   * its path. URLs of web pages such as YouTube videos are not direct and have to be resolved with yt-dlp.
   *
   * <p>Unlike the other methods of this class, this one accepts null, because it is typically asked about an optional
   * URL, such as one a source may or may not have. Null, empty, and malformed URLs are never direct.
   *
   * @param url the URL, or null
   * @return true if the path of the URL ends with a media extension, false for null, empty, or malformed URLs
   */
  public static boolean isDirectVideo(final @Nullable String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }
    try {
      final URI uri = URI.create(url);
      final String path = uri.getPath();
      if (path == null || path.isEmpty()) {
        return false;
      }
      final String extension = getExtension(path);
      return extension != null && MEDIA_EXTENSIONS.contains(extension);
    } catch (final IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Checks whether a string is the path of an existing file or directory.
   *
   * @param raw the string
   * @return true if the path exists
   */
  public static boolean isPath(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    try {
      final Path path = Path.of(raw);
      return Files.exists(path);
    } catch (final InvalidPathException exception) {
      return false;
    }
  }

  /**
   * Asks yt-dlp whether a URL is a live stream. This runs yt-dlp and may take a few seconds.
   *
   * @param url the URL of a web page or stream
   * @return true if yt-dlp reports the URL as live, false if it does not or if yt-dlp fails
   * @throws IllegalStateException if the library is still preparing yt-dlp in the background, so the answer is not
   *                               known yet
   */
  public static boolean isDynamicStream(final String url) {
    Preconditions.checkNotNull(url, "URL must not be null");
    final YTDLPParser parser = YTDLPParser.simple();
    return isDynamicStream(url, parser);
  }

  /**
   * Asks a parser whether a URL is a live stream.
   *
   * @param url    the URL of a web page or stream
   * @param parser the parser that resolves the URL
   * @return true if the parser reports the URL as live, false if it does not, if the URL is invalid, or if the
   * parser fails
   */
  @VisibleForTesting
  static boolean isDynamicStream(final String url, final YTDLPParser parser) {
    try {
      final URI uri = URI.create(url);
      final UriSource source = UriSource.uri(uri);
      final URLParseDump dump = parser.parse(source);
      return dump.is_live;
    } catch (final IOException | IllegalArgumentException | YTDLPParseException exception) {
      return false;
    }
  }

  private static @Nullable String getExtension(final String path) {
    final int lastSlash = path.lastIndexOf('/');
    final int lastBackslash = path.lastIndexOf('\\');
    final int lastSeparator = Math.max(lastSlash, lastBackslash);
    final int lastDot = path.lastIndexOf('.');
    final int lastIndex = path.length() - 1;
    final boolean hasExtension = lastDot > lastSeparator && lastDot < lastIndex;
    if (!hasExtension) {
      return null;
    }
    final String extension = path.substring(lastDot + 1);
    return extension.toLowerCase(Locale.ROOT);
  }
}
