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
 */
public class UriSourceDetector implements SourceDetector<UriSource> {

  /**
   * Constructs a new detector.
   */
  public UriSourceDetector() {
    // stateless
  }

  @Override
  public boolean isDetectedSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    return SourceUtils.isUri(raw);
  }

  @Override
  public UriSource createSource(final String raw) {
    Preconditions.checkNotNull(raw, "Raw must not be null");
    final URI uri = URI.create(raw);
    return UriSource.uri(uri);
  }

  @Override
  public int getPriority() {
    return SourceDetector.NORMAL_PRIORITY;
  }
}
