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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Interface representing the Random Dithering algorithm. This algorithm applies
 * a pseudo-random noise to the image data, modifying the pixel values to generate
 * a dithered effect. The randomness level is controlled by predefined weights
 * that determine the magnitude of noise introduced during the process.
 * Implementations of this interface are expected to operate with a specified
 * palette and weight to perform random dithering on image data.
 */
public interface RandomDither extends DitherAlgorithm {
  /**
   * Represents the light weight for the dithering algorithm.
   * This value is used to define the lowest level of randomness
   * or noise to be applied when dithering an image.
   */
  int LIGHT_WEIGHT = 32;
  /**
   * Represents the normal weight value used in the random dithering algorithm.
   * This constant defines a moderate level of influence or deviation
   * during the dithering process, providing a balance between minimal and heavy
   * randomness when processing pixel data.
   */
  int NORMAL_WEIGHT = 64;
  /**
   * Represents the predefined weight value for a "heavy" dithering effect.
   * This value is used within the {@code RandomDither} class to determine
   * the extent of randomness applied during the dithering process, specifically
   * favoring a stronger or more prominent random noise effect.
   */
  int HEAVY_WEIGHT = 128;
}
