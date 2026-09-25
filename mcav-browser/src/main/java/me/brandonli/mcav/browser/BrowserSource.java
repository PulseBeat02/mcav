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

import com.google.common.base.Preconditions;
import java.net.URI;
import me.brandonli.mcav.media.source.uri.UriSource;

/**
 * A web page to stream: its address, the size of the page and of the frames, and how many painted frames are skipped
 * between streamed frames. Only absolute {@code http} and {@code https} addresses with a host can be shown.
 */
public interface BrowserSource extends UriSource {
  /**
   * The frame width used by {@link #uri(URI)}.
   */
  int DEFAULT_WIDTH = 1280;

  /**
   * The frame height used by {@link #uri(URI)}.
   */
  int DEFAULT_HEIGHT = 720;

  /**
   * The largest width or height of a page.
   */
  int MAX_SIDE = HelperProtocol.MAX_SIDE;

  /**
   * The largest frame interval.
   */
  int MAX_FRAME_INTERVAL = 1000;

  /**
   * The longest address, in characters.
   */
  int MAX_ADDRESS_LENGTH = 65_536;

  /**
   * Creates a source with every setting.
   *
   * @param uri           the address of the page, an absolute {@code http} or {@code https} address
   * @param width         the width of the page and the frames in pixels, from 1 to {@value #MAX_SIDE}
   * @param height        the height of the page and the frames in pixels, from 1 to {@value #MAX_SIDE}
   * @param frameInterval stream every n-th painted frame, from 1 to {@value #MAX_FRAME_INTERVAL}; 1 streams every frame
   * @return the source
   * @throws IllegalArgumentException if the address is not a web address, is longer than {@value #MAX_ADDRESS_LENGTH}
   *                                  characters, or a number is out of range
   */
  static BrowserSource uri(final URI uri, final int width, final int height, final int frameInterval) {
    Preconditions.checkNotNull(uri, "URI must not be null");
    Preconditions.checkArgument(NavigationPolicy.isWebAddress(uri), "The browser shows http and https addresses only but got %s", uri);
    final int length = uri.toString().length();
    Preconditions.checkArgument(
      length <= MAX_ADDRESS_LENGTH,
      "An address has at most %s characters but had %s",
      MAX_ADDRESS_LENGTH,
      length
    );
    Preconditions.checkArgument(width > 0 && height > 0, "Frame size must be positive but was %sx%s", width, height);
    Preconditions.checkArgument(
      width <= MAX_SIDE && height <= MAX_SIDE,
      "Frame size must be at most %s per side but was %sx%s",
      MAX_SIDE,
      width,
      height
    );
    Preconditions.checkArgument(
      frameInterval > 0 && frameInterval <= MAX_FRAME_INTERVAL,
      "Frame interval must be between 1 and %s but was %s",
      MAX_FRAME_INTERVAL,
      frameInterval
    );
    return new BrowserSourceImpl(uri, width, height, frameInterval);
  }

  /**
   * Creates a source with the default settings: {@value #DEFAULT_WIDTH} by {@value #DEFAULT_HEIGHT} pixels, every
   * frame.
   *
   * @param uri the address of the page, an absolute {@code http} or {@code https} address
   * @return the source
   * @throws IllegalArgumentException if the address is not a web address
   */
  static BrowserSource uri(final URI uri) {
    return uri(uri, DEFAULT_WIDTH, DEFAULT_HEIGHT, 1);
  }

  /**
   * Gets the width of the page and of the frames.
   *
   * @return the width in pixels
   */
  int getWidth();

  /**
   * Gets the height of the page and of the frames.
   *
   * @return the height in pixels
   */
  int getHeight();

  /**
   * Gets how many painted frames make one streamed frame.
   *
   * @return the interval; 1 streams every frame
   */
  int getFrameInterval();

  /**
   * Gets the name of the source type.
   *
   * @return {@code browser}
   */
  @Override
  default String getName() {
    return "browser";
  }

  /**
   * Checks whether the address can be played directly. A web page is rendered by a browser, so it never can.
   *
   * @return false
   */
  @Override
  default boolean isDirect() {
    return false;
  }
}
