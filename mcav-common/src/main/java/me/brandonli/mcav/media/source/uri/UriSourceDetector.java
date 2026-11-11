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
 * Detects URIs with a scheme and a host.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class UriSourceDetector implements SourceDetector<UriSource> {

  private static final String RESOURCE_NULL_MESSAGE = "Resource must not be null!";

  /**
   * Creates a detector.
   *
   * @since 1.0.0
   */
  public UriSourceDetector() {}

  /**
   * Returns whether the string parses as a URI with a scheme and a host.
   *
   * @param resource the string entered by a user
   * @return true if {@link #createSource(String)} can create a source from it
   * @throws NullPointerException if {@code resource} is null
   * @since 1.0.0
   */
  @Override
  public boolean isDetectedSource(final String resource) {
    Preconditions.checkNotNull(resource, RESOURCE_NULL_MESSAGE);

    return SourceUtils.isUri(resource);
  }

  /**
   * Creates a URI source for the URI the string holds.
   *
   * @param resource the string entered by a user
   * @return the source
   * @throws IllegalArgumentException if the string is not syntactically a URI
   * @throws NullPointerException     if {@code resource} is null
   * @since 1.0.0
   */
  @Override
  public UriSource createSource(final String resource) {
    Preconditions.checkNotNull(resource, RESOURCE_NULL_MESSAGE);

    final URI uri = URI.create(resource);
    return UriSource.uri(uri);
  }

  /**
   * Returns {@link SourceDetector#NORMAL_PRIORITY}, so the device, FFmpeg and file detectors win when they accept the same string.
   *
   * @return the priority, higher wins
   * @since 1.0.0
   */
  @Override
  public int getPriority() {
    return SourceDetector.NORMAL_PRIORITY;
  }
}
