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

import java.net.URI;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link BrowserSource}, created by {@link BrowserSource#uri(URI, int, int, int)} with validated
 * settings. Two sources are equal when every setting is equal.
 */
public final class BrowserSourceImpl implements BrowserSource {

  private final URI uri;
  private final int width;
  private final int height;
  private final int frameInterval;

  BrowserSourceImpl(final URI uri, final int width, final int height, final int frameInterval) {
    this.uri = uri;
    this.width = width;
    this.height = height;
    this.frameInterval = frameInterval;
  }

  /**
   * Gets the width of the page and of the frames.
   *
   * @return the width in pixels
   */
  @Override
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the page and of the frames.
   *
   * @return the height in pixels
   */
  @Override
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets how many painted frames make one streamed frame.
   *
   * @return the interval; 1 streams every frame
   */
  @Override
  public int getFrameInterval() {
    return this.frameInterval;
  }

  /**
   * Gets the address of the page.
   *
   * @return the address
   */
  @Override
  public URI getUri() {
    return this.uri;
  }

  /**
   * Checks whether another object is a browser source with the same address and settings.
   *
   * @param other the object to compare with
   * @return true if every setting is equal
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final BrowserSourceImpl source)) {
      return false;
    }
    return (
      this.uri.equals(source.uri) &&
      this.width == source.width &&
      this.height == source.height &&
      this.frameInterval == source.frameInterval
    );
  }

  /**
   * Computes a hash code from the address and every setting.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.uri, this.width, this.height, this.frameInterval);
  }

  /**
   * Describes the source by its address, size, and frame interval.
   *
   * @return a text such as {@code BrowserSource[https://example.org, 1280x720, every 1]}
   */
  @Override
  public String toString() {
    return "BrowserSource[" + this.uri + ", " + this.width + "x" + this.height + ", every " + this.frameInterval + "]";
  }
}
