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
package me.brandonli.mcav.media.source.uri;

import com.google.common.base.Preconditions;
import java.net.URI;
import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.utils.SourceUtils;

/**
 * Detects URLs with a scheme and a host.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public class UriSourceDetector implements SourceDetector<UriSource> {

  private static final String RAW_NULL_MESSAGE = "Raw must not be null!";

  /**
   * Constructs a new detector.
   *
   * @since 1.0.0
   */
  public UriSourceDetector() {}

  /**
   * Checks whether the string parses as a URI with a scheme and a host.
   *
   * @param raw the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @throws NullPointerException if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    return SourceUtils.isUri(raw);
  }

  /**
   * Creates a URI source for the URI the string holds.
   *
   * @param raw the string entered by a user
   * @return the source
   * @throws IllegalArgumentException if the string is not syntactically a URI
   * @throws NullPointerException     if {@code raw} is null
   * @since 1.0.0
   */
  @Override
  public UriSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, RAW_NULL_MESSAGE);

    final URI uri = URI.create(raw);
    return UriSource.uri(uri);
  }

  /**
   * Returns {@link SourceDetector#NORMAL_PRIORITY}, so device, FFmpeg and file detectors are asked first.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  @Override
  public int getPriority() {
    return SourceDetector.NORMAL_PRIORITY;
  }
}
