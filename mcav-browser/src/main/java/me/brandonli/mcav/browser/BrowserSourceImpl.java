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
import me.brandonli.mcav.media.source.UriSourceImpl;

/**
 * Implementation of the {@link BrowserSource} interface.
 */
public class BrowserSourceImpl extends UriSourceImpl implements BrowserSource {

  private final int quality;
  private final int width;
  private final int height;
  private final int nthFrame;

  BrowserSourceImpl(final URI uri, final int quality, final int width, final int height, final int nthFrame) {
    super(uri);
    this.quality = quality;
    this.width = width;
    this.height = height;
    this.nthFrame = nthFrame;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScreencastQuality() {
    return this.quality;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScreencastWidth() {
    return this.width;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScreencastHeight() {
    return this.height;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScreencastNthFrame() {
    return this.nthFrame;
  }
}
