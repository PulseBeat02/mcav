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
package me.brandonli.mcav.sandbox.utils;

import me.brandonli.mcav.media.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.video.dither.palette.Palette;

public enum DitherAlgorithms {
  ;


  
  private static DitherAlgorithm nearest() {
    return DitherAlgorithm.nearest().withPalette(Palette.DEFAULT).build();
  }

  private static DitherAlgorithm random(final int weight) {
    return DitherAlgorithm.random().withPalette(Palette.DEFAULT).withWeight(weight).build();
  }

  private static DitherAlgorithm errorDiffusion(final ErrorDiffusionDitherBuilder.Algorithm type) {
    return DitherAlgorithm.errorDiffusion().withAlgorithm(type).withPalette(Palette.DEFAULT).build();
  }
}
