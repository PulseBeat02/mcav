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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * A dithering algorithm that can split an image into independent parts and dither them on several threads.
 * Displays that own a thread pool, such as {@code CompressedMapResult}, use this interface to dither large map
 * walls faster.
 */
public interface ParallelDitherAlgorithm extends DitherAlgorithm {
  /**
   * Dithers an image into palette indices using the threads of a pool. The result is identical, or nearly
   * identical, to {@link #ditherIntoBytes(ImageBuffer)}.
   *
   * @param buffer the image to dither, which must not be modified while the method runs
   * @param pool   the pool that runs the work
   * @return the palette index of every pixel, laid out row by row
   */
  byte[] ditherIntoBytes(final ImageBuffer buffer, final ForkJoinPool pool);
}
