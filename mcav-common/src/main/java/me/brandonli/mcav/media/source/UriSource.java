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
package me.brandonli.mcav.media.source;

import com.google.common.base.Preconditions;
import java.net.URI;

/**
 * Represents a type of {@link DynamicSource} that is associated with a specific URI.
 */
public interface UriSource extends DynamicSource {
  /**
   * Retrieves the URI associated with this source.
   *
   * @return the {@link URI} representing the location of the resource.
   */
  URI getUri();

  /**
   * Retrieves the default name associated with this source.
   *
   * @return the default name of the source as a {@code String}, which is "uri".
   */
  @Override
  default String getName() {
    return "uri";
  }

  /**
   * Retrieves the resource associated with this URI source as a string.
   * The resource is derived from the URI represented by this source.
   *
   * @return the resource as a string, which is the string representation of the associated URI.
   */
  @Override
  default String getResource() {
    return this.getUri().toString();
  }

  /**
   * Creates a new {@link UriSource} instance for the specified URI.
   *
   * @param uri the {@link URI} representing the resource location. Must not be null.
   * @return a new {@link UriSource} instance associated with the specified URI.
   * @throws NullPointerException if the specified URI is null.
   */
  static UriSource uri(final URI uri) {
    Preconditions.checkNotNull(uri);
    return new UriSourceImpl(uri);
  }
}
