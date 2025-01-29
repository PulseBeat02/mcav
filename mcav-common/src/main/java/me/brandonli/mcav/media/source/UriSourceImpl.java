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

/**
 * An implementation of the {@link UriSource} interface that encapsulates a specific URI.
 * This class provides a concrete implementation for accessing the URI associated with a source.
 * It serves as the default implementation of a URI-based source in the library.
 */
public class UriSourceImpl implements UriSource {

  private final URI uri;

  public UriSourceImpl(final URI uri) {
    this.uri = uri;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URI getUri() {
    return this.uri;
  }
}
