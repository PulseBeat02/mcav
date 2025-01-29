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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;

/**
 * Interface representing the Random Dithering algorithm.
 */
public interface RandomDither extends DitherAlgorithm {
  /**
   * Represents the light weight for the dithering algorithm.
   */
  int LIGHT_WEIGHT = 32;
  /**
   * Represents the normal weight value used in the random dithering algorithm.
   */
  int NORMAL_WEIGHT = 64;
  /**
   * Represents the predefined weight value for a "heavy" dithering effect.
   */
  int HEAVY_WEIGHT = 128;
}
