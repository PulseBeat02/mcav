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
import me.brandonli.mcav.media.source.UriSource;

/**
 * Represents a source for browser-based media playback.
 */
public interface BrowserSource extends UriSource {
  /**
   * Gets the quality of the screencast.
   *
   * @return the quality of the screencast
   */
  int getScreencastQuality();

  /**
   * Gets the width of the screencast.
   *
   * @return the width of the screencast
   */
  int getScreencastWidth();

  /**
   * Gets the height of the screencast.
   *
   * @return the height of the screencast
   */
  int getScreencastHeight();

  /**
   * Gets the nth frame to capture from the screencast.
   *
   * @return the nth frame to capture
   */
  int getScreencastNthFrame();

  /**
   * Creates a new instance of {@link BrowserSource} with the specified parameters.
   *
   * @param uri the URI of the browser source
   * @param quality the quality of the screencast
   * @param width the width of the screencast
   * @param height the height of the screencast
   * @param nthFrame the nth frame to capture from the screencast
   * @return a new instance of {@link BrowserSource}
   */
  static BrowserSource uri(final URI uri, final int quality, final int width, final int height, final int nthFrame) {
    return new BrowserSourceImpl(uri, quality, width, height, nthFrame);
  }
}
