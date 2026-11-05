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
package me.brandonli.mcav.testing;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;

// Loads the OpenCV natives ahead of timed tests. The first image of a JVM loads them on the thread that makes it, which
// takes seconds on a busy machine.
public final class OpenCvNatives {

  private OpenCvNatives() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void load() {
    final ImageBuffer warmUp = ImageBuffer.bytes(new byte[3], 1, 1);
    final ResizeFilter resize = new ResizeFilter(2, 2);
    resize.applyFilter(warmUp);
    warmUp.release();
  }
}
