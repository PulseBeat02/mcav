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
import java.util.Objects;
import me.brandonli.mcav.utils.SourceUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link UriSource}.
 */
public final class UriSourceImpl implements UriSource {

  private final URI uri;
  private final boolean direct;

  UriSourceImpl(final URI uri) {
    this.uri = uri;
    final String raw = uri.toString();
    this.direct = SourceUtils.isDirectVideo(raw);
  }

  @Override
  public URI getUri() {
    return this.uri;
  }

  @Override
  public boolean isDirect() {
    return this.direct;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final UriSourceImpl source)) {
      return false;
    }
    return this.uri.equals(source.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.uri);
  }

  @Override
  public String toString() {
    return "UriSource[" + this.uri + "]";
  }
}
