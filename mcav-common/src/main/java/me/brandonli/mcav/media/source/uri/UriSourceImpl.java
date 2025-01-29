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

import java.net.URI;
import me.brandonli.mcav.utils.SourceUtils;

/**
 * An implementation of the {@link UriSource} interface.
 */
public class UriSourceImpl implements UriSource {

  private final URI uri;
  private final boolean direct;

  UriSourceImpl(final URI uri) {
    this.uri = uri;
    this.direct = SourceUtils.isDirectVideo(uri.toString());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public URI getUri() {
    return this.uri;
  }

  @Override
  public boolean isDirect() {
    return this.direct;
  }
}
