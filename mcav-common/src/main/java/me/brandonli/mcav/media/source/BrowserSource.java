/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.net.URI;

public interface BrowserSource extends UriSource {
  int getScreencastQuality();

  int getScreencastWidth();

  int getScreencastHeight();

  int getScreencastNthFrame();

  static BrowserSource uri(final URI uri, final int quality, final int width, final int height, final int nthFrame) {
    return new BrowserSourceImpl(uri, quality, width, height, nthFrame);
  }
}
