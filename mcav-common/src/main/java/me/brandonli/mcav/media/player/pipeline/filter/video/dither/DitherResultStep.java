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
 * A display that shows dithered frames, such as a wall of maps. Result steps receive the raw frame and the
 * algorithm, so they can decide how to dither, for example in parallel or with a byte budget per frame.
 */
public interface DitherResultStep {
  /**
   * Dithers and displays a frame. Called on the video render thread of the player for every frame.
   *
   * @param samples   the frame, which may be modified
   * @param algorithm the dithering algorithm to use
   */
  void process(final ImageBuffer samples, final DitherAlgorithm algorithm);

  /**
   * Prepares the display. Called once before the first frame.
   */
  void start();

  /**
   * Removes the display and releases its resources. Called once after the last frame.
   */
  void release();
}
