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

import com.google.common.base.MoreObjects;
import java.net.URI;
import java.util.Objects;
import me.brandonli.mcav.utils.SourceUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link UriSource}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class DefaultUriSource implements UriSource {

  private final URI uri;
  private final boolean direct;

  DefaultUriSource(final URI uri) {
    this.uri = uri;
    final String raw = uri.toString();
    this.direct = SourceUtils.isDirectVideo(raw);
  }

  /**
   * Returns the URI this source was created with.
   *
   * @return the URL
   * @since 1.0.0
   */
  @Override
  public URI getUri() {
    return this.uri;
  }

  /**
   * Returns whether the URL points directly at a media file, judged by its file extension when this source was created, rather than at
   * a web page that has to be resolved first.
   *
   * @return true if the URL looks like a direct media link
   * @since 1.0.0
   */
  @Override
  public boolean isDirect() {
    return this.direct;
  }

  /**
   * Returns whether {@code other} is an URI source with the same URI.
   *
   * @param other the object to compare with
   * @return whether both match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final DefaultUriSource uriSource)) {
      return false;
    }

    return this.uri.equals(uriSource.uri);
  }

  /**
   * Returns a hash code built from the URI.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.uri);
  }

  /**
   * Returns a debugging representation with the URI.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("uri", this.uri).add("direct", this.direct).toString();
  }
}
