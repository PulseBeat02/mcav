/*
 * This file is part of mcav, a media playback library for Minecraft
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
 * This class contains functionality for determining the type of a resource,
 * validating its format, and inspecting dynamic stream characteristics.
 * <p>
 * This class is not instantiable as it is designed to only provide static utility methods.
 */
public final class SourceUtils {

  private SourceUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * A static list of pairs used for determining the appropriate {@link Source}
   * implementation based on a validation function. Each pair consists of:
   * 1. A {@link Function} that validates whether a given string matches a specific
   * criteria (e.g., whether it is a file path or URI).
   * 2. A {@link Class} representing the {@link Source} implementation associated
   * with the validated criteria.
   * <p>
   * The list currently supports:
   * - Validation for file paths, mapping to {@link FileSource}.
   * - Validation for URIs, also mapping to {@link FileSource}.
   * <p>
   * This structure facilitates identifying the type of input (e.g., path or URI)
   * and resolving the corresponding {@link Source} class dynamically in utilities like
   * {@link SourceUtils#getSource(String)}.
   * <p>
   * This field is immutable and initialized statically, ensuring thread-safe
   * usage across multiple operations.
   */
  private static final List<Pair<Function<String, Boolean>, Class<? extends Source>>> SOURCE_CONSTRUCTORS = List.of(
    Pair.pair(SourceUtils::isPath, FileSource.class),
    Pair.pair(SourceUtils::isUri, FileSource.class)
  );

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
   * Identifies and retrieves the class type of a resource based on predefined rules.
   * Iterates through a list of functions and their associated resource types
   * to determine the appropriate class for the given resource.
   *
   * @param resource the input resource, typically represented as a string,
   *                 such as a file path or URI. Must not be null.
   * @return the class type of the resource if a matching rule is found;
   * {@code null} if no matching rule is applicable.
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
   * Determines if the given URL corresponds to a dynamic (live) stream.
   * <p>
   * This method leverages the YTDLPParser to analyze the URL and checks the
   * "is_live" property from the parsed {@link URLParseDump} object.
   * If an I/O error occurs during parsing, it returns {@code false}.
   *
   * @param url the URL to analyze. It must be a valid string representation of a URI.
   * @return {@code true} if the URL corresponds to a live stream, {@code false} otherwise.
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
