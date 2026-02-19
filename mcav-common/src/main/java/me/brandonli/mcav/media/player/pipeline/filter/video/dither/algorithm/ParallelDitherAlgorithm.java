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
 * A {@link DitherAlgorithm} whose per-pixel computations are mutually independent and can
 * therefore be executed in parallel across many threads.
 *
 * <p>Callers that hold a pre-warmed {@link ForkJoinPool}.
 */
public interface ParallelDitherAlgorithm extends DitherAlgorithm {
  /**
   * Converts the given image buffer into palette-index bytes using parallel computation.
   *
   * @param buffer the image to dither; must remain unmodified during the call
   * @param pool   the {@link ForkJoinPool} to use for parallel work
   * @return a byte array of palette indices, one per pixel, in row-major order
   */
  byte[] ditherIntoBytes(ImageBuffer buffer, ForkJoinPool pool);
}
