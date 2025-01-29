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
 * An implementation of the {@code BrowserSource} interface that extends the {@code UriSourceImpl} class.
 * This class represents a media source accessible via a URI and contains additional video metadata.
 * <p>
 * Instances of this class encapsulate a URI for the media source and metadata used to describe
 * the video properties, such as width, height, bitrate, and frame rate. The metadata can be accessed
 * for purposes such as playback or analysis.
 */
public class BrowserSourceImpl extends UriSourceImpl implements BrowserSource {

  private final VideoMetadata metadata;

  /**
   * Constructs a new instance of {@code BrowserSourceImpl} with the specified URI and video metadata.
   *
   * @param uri      the URI representing the location of the media source
   * @param metadata the video metadata associated with the media source
   */
  public BrowserSourceImpl(final URI uri, final VideoMetadata metadata) {
    super(uri);
    this.metadata = metadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoMetadata getMetadata() {
    return this.metadata;
  }
}
