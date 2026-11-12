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

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.DiffusionKernel;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.ErrorDiffusionDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class DefaultErrorDiffusionDitherBuilderTest {

  @Test
  void shouldBuildFilterLiteOnTheMapPaletteByDefault() {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDither built = builder.build();
    final DiffusionKernel kernel = built.getKernel();
    final DitherPalette palette = built.getPalette();
    assertSame(DiffusionKernel.FILTER_LITE, kernel);
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  @Test
  void shouldRejectAnErrorStrengthThatIsNotANumber() {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    assertThrows(IllegalArgumentException.class, () -> builder.errorStrength(Float.NaN));
  }

  @ParameterizedTest
  @EnumSource(ErrorDiffusionDitherBuilder.Algorithm.class)
  void shouldCreateANewAlgorithmOnEveryBuild(final ErrorDiffusionDitherBuilder.Algorithm algorithm) {
    final ErrorDiffusionDitherBuilder builder = DitherAlgorithm.errorDiffusion();
    final ErrorDiffusionDitherBuilder selected = builder.algorithm(algorithm);
    final ErrorDiffusionDither first = selected.build();
    final ErrorDiffusionDither second = selected.build();
    assertNotSame(first, second, "a temporal algorithm holds the state of one video, so no build hands out an earlier one");
  }
}
