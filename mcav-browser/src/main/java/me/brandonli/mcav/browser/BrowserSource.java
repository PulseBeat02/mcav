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
 * A web page to stream, together with the screencast settings: the JPEG quality of the frames, the size the
 * frames are scaled to, and how many browser frames are skipped between streamed frames.
 */
public interface BrowserSource extends UriSource {
  /**
   * The JPEG quality used by {@link #uri(URI)}.
   */
  int DEFAULT_QUALITY = 80;

  /**
   * The frame width used by {@link #uri(URI)}.
   */
  int DEFAULT_WIDTH = 1280;

  /**
   * The frame height used by {@link #uri(URI)}.
   */
  int DEFAULT_HEIGHT = 720;

  /**
   * Creates a source with every setting.
   *
   * @param uri      the address of the page
   * @param quality  the JPEG quality of the frames from 0 to 100
   * @param width    the width of the frames in pixels
   * @param height   the height of the frames in pixels
   * @param nthFrame stream every n-th browser frame; 1 streams every frame
   * @return the source
   */
  static BrowserSource uri(final URI uri, final int quality, final int width, final int height, final int nthFrame) {
    Preconditions.checkNotNull(uri, "URI must not be null");
    Preconditions.checkArgument(quality >= 0 && quality <= 100, "Quality must be between 0 and 100 but was %s", quality);
    Preconditions.checkArgument(width > 0 && height > 0, "Frame size must be positive but was %sx%s", width, height);
    Preconditions.checkArgument(nthFrame > 0, "Frame interval must be positive but was %s", nthFrame);
    return new BrowserSourceImpl(uri, quality, width, height, nthFrame);
  }

  /**
   * Creates a source with the default settings: quality {@value #DEFAULT_QUALITY}, {@value #DEFAULT_WIDTH} by
   * {@value #DEFAULT_HEIGHT} pixels, every frame.
   *
   * @param uri the address of the page
   * @return the source
   */
  static BrowserSource uri(final URI uri) {
    return uri(uri, DEFAULT_QUALITY, DEFAULT_WIDTH, DEFAULT_HEIGHT, 1);
  }

  /**
   * Gets the JPEG quality of the frames.
   *
   * @return the quality from 0 to 100
   */
  int getScreencastQuality();

  /**
   * Gets the width of the frames.
   *
   * @return the width in pixels
   */
  int getScreencastWidth();

  /**
   * Gets the height of the frames.
   *
   * @return the height in pixels
   */
  int getScreencastHeight();

  /**
   * Gets how many browser frames are skipped between streamed frames.
   *
   * @return the interval; 1 streams every frame
   */
  int getScreencastNthFrame();

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
