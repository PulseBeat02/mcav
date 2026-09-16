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
import me.brandonli.mcav.media.source.DynamicSource;

/**
 * Media behind a URL, such as an HTTP stream, an RTSP camera, or a web page that has to be resolved with yt-dlp
 * first.
 */
public interface UriSource extends DynamicSource {
  /**
   * Creates a source for a URL.
   *
   * @param uri the URL
   * @return the source
   */
  static UriSource uri(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null");
    return new UriSourceImpl(uri);
  }

  /**
   * Gets the URL.
   *
   * @return the URL
   */
  URI getUri();

  /**
   * Checks whether the URL points directly at a media file, judging by its file extension, rather than at a web
   * page that has to be resolved first.
   *
   * @return true if the URL looks like a direct media link
   */
  boolean isDirect();

  @Override
  default String getName() {
    return "uri";
  }

  @Override
  default String getResource() {
    final URI uri = this.getUri();
    return uri.toString();
  }
}
