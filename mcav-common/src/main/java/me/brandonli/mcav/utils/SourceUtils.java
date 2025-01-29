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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.immutable.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility class providing helper methods for working with resource sources.
 */
public final class SourceUtils {

  private SourceUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static final List<Pair<Function<String, Boolean>, Class<? extends Source>>> SOURCE_CONSTRUCTORS = List.of(
    Pair.pair(SourceUtils::isPath, FileSource.class),
    Pair.pair(SourceUtils::isUri, FileSource.class)
  );

  /**
   * Checks if the provided source is a GIF image.
   *
   * @param source the source to check
   * @return true if the source is a GIF image, false otherwise
   */
  public static boolean isImageGif(final Source source) {
    if (source instanceof final FileSource fileSource) {
      final Path path = fileSource.getPath();
      final String rawPath = path.toString();
      final String lower = rawPath.toLowerCase();
      return lower.endsWith(".gif");
    } else if (source instanceof final UriSource uriSource) {
      final URI uri = uriSource.getUri();
      final String rawPath = uri.toString();
      final String lower = rawPath.toLowerCase();
      return lower.endsWith(".gif");
    }
    return false;
  }

  /**
   * Determines whether the provided string is a valid URI with both a scheme and a host.
   *
   * @param raw the string to evaluate as a URI
   * @return true if the given string represents a valid URI with a scheme and a host, false otherwise
   */
  public static boolean isUri(final String raw) {
    try {
      final URI uri = URI.create(raw);
      return uri.getScheme() != null && uri.getHost() != null;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Determines if a given URL represents a direct video file based on its format.
   * This method checks if the URL contains a valid path with a file extension.
   *
   * @param url the URL string to check. It should be a valid URI string.
   * @return true if the URL corresponds to a direct video file, false otherwise
   */
  public static boolean isDirectVideoFile(final String url) {
    if (url == null || url.isEmpty()) {
      return false;
    }
    try {
      final URI uri = URI.create(url);
      final String path = uri.getPath();
      if (path == null || path.isEmpty()) {
        return false;
      }
      final int lastSlashIndex = path.lastIndexOf('/');
      final int lastDotIndex = path.lastIndexOf('.');
      return lastDotIndex > lastSlashIndex && lastDotIndex < path.length() - 1;
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Determines if a given raw string represents a valid file path on the system.
   *
   * @param raw the string to be validated as a path
   * @return {@code true} if the string is a valid path and the file or directory exists, {@code false} otherwise
   */
  public static boolean isPath(final String raw) {
    try {
      final Path path = Path.of(raw);
      return Files.exists(path);
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Returns the source class for a given resource string.
   * @param resource the resource string to check
   * @return the class of the source if it matches any known source type, or null if no match is found
   */
  public static @Nullable Class<?> getSource(final String resource) {
    for (final Pair<Function<String, Boolean>, Class<? extends Source>> pair : SOURCE_CONSTRUCTORS) {
      final Function<String, Boolean> function = pair.getFirst();
      final boolean result = function.apply(resource);
      if (!result) {
        continue;
      }
      return pair.getSecond();
    }
    return null;
  }

  /**
   * Checks if the provided URL is a dynamic stream (live stream).
   *
   * @param url the URL to check
   * @return true if the URL is a dynamic stream, false otherwise
   */
  public static boolean isDynamicStream(final String url) {
    try {
      final YTDLPParser parser = YTDLPParser.simple();
      final UriSource source = UriSource.uri(URI.create(url));
      final URLParseDump dump = parser.parse(source);
      return dump.is_live;
    } catch (final IOException e) {
      return false;
    }
  }
}
