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
package me.brandonli.mcav.browser;

import com.google.common.base.MoreObjects;
import java.net.URI;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultBrowserSource implements BrowserSource {

  private final URI uri;
  private final int width;
  private final int height;
  private final int frameInterval;

  DefaultBrowserSource(final URI uri, final int width, final int height, final int frameInterval) {
    this.uri = uri;
    this.width = width;
    this.height = height;
    this.frameInterval = frameInterval;
  }

  @Override
  public int getWidth() {
    return this.width;
  }

  @Override
  public int getHeight() {
    return this.height;
  }

  @Override
  public int getFrameInterval() {
    return this.frameInterval;
  }

  @Override
  public URI getUri() {
    return this.uri;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final DefaultBrowserSource source)) {
      return false;
    }

    return (
      this.uri.equals(source.uri) &&
      this.width == source.width &&
      this.height == source.height &&
      this.frameInterval == source.frameInterval
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.uri, this.width, this.height, this.frameInterval);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("uri", this.uri)
      .add("width", this.width)
      .add("height", this.height)
      .add("frameInterval", this.frameInterval)
      .toString();
  }
}
