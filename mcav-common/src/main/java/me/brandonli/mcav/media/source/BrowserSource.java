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
package me.brandonli.mcav.media.source;

import java.net.URI;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;

/**
 * Represents a browser-based media source that extends the capabilities of a URI source
 * and provides additional video metadata.
 * <p>
 * The {@code BrowserSource} interface is intended for use in scenarios where media
 * content is accessed via a URI and requires metadata for playback or analysis purposes.
 * Instances of this interface are created using the {@code uri()} static factory method.
 */
public interface BrowserSource extends UriSource {
  /**
   * Retrieves metadata associated with the video source.
   *
   * @return an instance of {@code VideoMetadata} containing details
   * about the video, such as width, height, bitrate, and frame rate
   */
  VideoMetadata getMetadata();

  /**
   * Creates a new instance of {@code BrowserSource} using the provided URI and video metadata.
   *
   * @param uri      the URI representing the media source, must not be null
   * @param metadata the video metadata associated with the URI, must not be null
   * @return a {@code BrowserSource} instance encapsulating the specified URI and video metadata
   */
  static BrowserSource uri(final URI uri, final VideoMetadata metadata) {
    return new BrowserSourceImpl(uri, metadata);
  }
}
