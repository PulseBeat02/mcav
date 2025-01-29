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

import java.util.function.Supplier;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.BayerDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered.PixelMapper;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random.RandomDither;

public enum DitheringArgument {
  // Error diffusion dithering algorithms
  FILTER_LITE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.FILTER_LITE)),
  FLOYD_STEINBERG(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.FLOYD_STEINBERG)),
  JARVIS_JUDICE_NINKE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.JARVIS_JUDICE_NINKE)),
  STUCKI(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.STUCKI)),
  ATKINSON(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.ATKINSON)),
  STEVENSON_ARCE(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.STEVENSON_ARCE)),
  BURKES(() -> errorDiffusion(ErrorDiffusionDitherBuilder.Algorithm.BURKES)),

  // Random dithering algorithms
  RANDOM_LIGHT_WEIGHT(() -> random(RandomDither.LIGHT_WEIGHT)),
  RANDOM_NORMAL_WEIGHT(() -> random(RandomDither.NORMAL_WEIGHT)),
  RANDOM_HEAVY_WEIGHT(() -> random(RandomDither.HEAVY_WEIGHT)),

  // Nearest color dithering algorithm
  NEAREST_COLOR(() -> nearest()),

  // Bayer dithering algorithms
  BAYER_2X2_LIGHT(() -> bayer(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAX, PixelMapper.MIN_STRENGTH)),
  BAYER_2X2_NORMAL(() -> bayer(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAX, PixelMapper.NORMAL_STRENGTH)),
  BAYER_2X2_HEAVY(() -> bayer(BayerDither.NORMAL_2X2, BayerDither.NORMAL_2X2_MAX, PixelMapper.MAX_STRENGTH)),
  BAYER_4X4_LIGHT(() -> bayer(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAX, PixelMapper.MIN_STRENGTH)),
  BAYER_4X4_NORMAL(() -> bayer(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAX, PixelMapper.NORMAL_STRENGTH)),
  BAYER_4X4_HEAVY(() -> bayer(BayerDither.NORMAL_4X4, BayerDither.NORMAL_4X4_MAX, PixelMapper.MAX_STRENGTH)),
  BAYER_8X8_LIGHT(() -> bayer(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAX, PixelMapper.MIN_STRENGTH)),
  BAYER_8X8_NORMAL(() -> bayer(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAX, PixelMapper.NORMAL_STRENGTH)),
  BAYER_8X8_HEAVY(() -> bayer(BayerDither.NORMAL_8X8, BayerDither.NORMAL_8X8_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_6X6_LIGHT(() -> bayer(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAX, PixelMapper.MIN_STRENGTH)),
  CLUSTERED_DOT_6X6_NORMAL(() -> bayer(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAX, PixelMapper.NORMAL_STRENGTH)),
  CLUSTERED_DOT_6X6_HEAVY(() -> bayer(BayerDither.CLUSTERED_DOT_6X6, BayerDither.CLUSTERED_DOT_6X6_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_8X8_LIGHT(() -> bayer(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAX, PixelMapper.MIN_STRENGTH)),
  CLUSTERED_DOT_8X8_NORMAL(() -> bayer(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAX, PixelMapper.NORMAL_STRENGTH)),
  CLUSTERED_DOT_8X8_HEAVY(() -> bayer(BayerDither.CLUSTERED_DOT_8X8, BayerDither.CLUSTERED_DOT_8X8_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_6X6_2_LIGHT(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAX, PixelMapper.MIN_STRENGTH)),
  CLUSTERED_DOT_6X6_2_NORMAL(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_6X6_2_HEAVY(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_2, BayerDither.CLUSTERED_DOT_6X6_2_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_6X6_3_LIGHT(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAX, PixelMapper.MIN_STRENGTH)),
  CLUSTERED_DOT_6X6_3_NORMAL(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_6X6_3_HEAVY(() -> bayer(BayerDither.CLUSTERED_DOT_6X6_3, BayerDither.CLUSTERED_DOT_6X6_3_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_DIAGONAL_8X8_3_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_3_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_3_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_3_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_4X4_LIGHT(() -> bayer(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAX, PixelMapper.MIN_STRENGTH)),
  CLUSTERED_DOT_4X4_NORMAL(() -> bayer(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAX, PixelMapper.NORMAL_STRENGTH)),
  CLUSTERED_DOT_4X4_HEAVY(() -> bayer(BayerDither.CLUSTERED_DOT_4X4, BayerDither.CLUSTERED_DOT_4X4_MAX, PixelMapper.MAX_STRENGTH)),
  VERTICAL_5X3_LIGHT(() -> bayer(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAX, PixelMapper.MIN_STRENGTH)),
  VERTICAL_5X3_NORMAL(() -> bayer(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAX, PixelMapper.NORMAL_STRENGTH)),
  VERTICAL_5X3_HEAVY(() -> bayer(BayerDither.VERTICAL_5X3, BayerDither.VERTICAL_5X3_MAX, PixelMapper.MAX_STRENGTH)),
  HORIZONTAL_3X5_LIGHT(() -> bayer(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAX, PixelMapper.MIN_STRENGTH)),
  HORIZONTAL_3X5_NORMAL(() -> bayer(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAX, PixelMapper.NORMAL_STRENGTH)),
  HORIZONTAL_3X5_HEAVY(() -> bayer(BayerDither.HORIZONTAL_3X5, BayerDither.HORIZONTAL_3X5_MAX, PixelMapper.MAX_STRENGTH)),
  CLUSTERED_DOT_DIAGONAL_6X6_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_6X6_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_6X6_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_6X6, BayerDither.CLUSTERED_DOT_DIAGONAL_6X6_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_2_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_2_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_2_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_2_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_16X16_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_16X16_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_16X16_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_16X16, BayerDither.CLUSTERED_DOT_DIAGONAL_16X16_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_SPIRAL_5X5_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_SPIRAL_5X5_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_SPIRAL_5X5_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_SPIRAL_5X5, BayerDither.CLUSTERED_DOT_SPIRAL_5X5_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_HORIZONTAL_LINE_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_HORIZONTAL_LINE_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_HORIZONTAL_LINE_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE, BayerDither.CLUSTERED_DOT_HORIZONTAL_LINE_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_VERTICAL_LINE_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_VERTICAL_LINE_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_VERTICAL_LINE_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_VERTICAL_LINE, BayerDither.CLUSTERED_DOT_VERTICAL_LINE_MAX, PixelMapper.MAX_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_LIGHT(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAX, PixelMapper.MIN_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_NORMAL(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAX, PixelMapper.NORMAL_STRENGTH)
  ),
  CLUSTERED_DOT_DIAGONAL_8X8_HEAVY(() ->
    bayer(BayerDither.CLUSTERED_DOT_DIAGONAL_8X8, BayerDither.CLUSTERED_DOT_DIAGONAL_8X8_MAX, PixelMapper.MAX_STRENGTH)
  );

  private static DitherAlgorithm bayer(final int[][] matrix, final int max, final float strength) {
    return DitherAlgorithm.ordered().withDitherMatrix(PixelMapper.ofPixelMapper(matrix, max, strength)).build();
  }

  private static DitherAlgorithm nearest() {
    return DitherAlgorithm.nearest().build();
  }

  private static DitherAlgorithm random(final int weight) {
    return DitherAlgorithm.random().withWeight(weight).build();
  }

  private static DitherAlgorithm errorDiffusion(final ErrorDiffusionDitherBuilder.Algorithm type) {
    return DitherAlgorithm.errorDiffusion().withAlgorithm(type).build();
  }

  private final Supplier<DitherAlgorithm> algorithmSupplier;
  private volatile DitherAlgorithm algorithm;

  // lazily load algorithm
  DitheringArgument(final Supplier<DitherAlgorithm> algorithmSupplier) {
    this.algorithmSupplier = algorithmSupplier;
  }

  public DitherAlgorithm getAlgorithm() {
    if (this.algorithm == null) {
      synchronized (this) {
        if (this.algorithm == null) {
          this.algorithm = this.algorithmSupplier.get();
        }
      }
    }
    return this.algorithm;
  }
}
