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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Represents an interface used to handle the result of a dithering operation.
 */
public interface DitherResultStep {
  /**
   * Processes the dithered video output and its associated metadata.
   *
   * @param samples  the {@link ImageBuffer} containing the original image samples
   * @param algorithm the {@link DitherAlgorithm} used for dithering
   */
  void process(final ImageBuffer samples, final DitherAlgorithm algorithm);

  /**
   * Starts the dithering result process.
   */
  void start();

  /**
   * Releases any resources associated with this step.
   */
  void release();
}
