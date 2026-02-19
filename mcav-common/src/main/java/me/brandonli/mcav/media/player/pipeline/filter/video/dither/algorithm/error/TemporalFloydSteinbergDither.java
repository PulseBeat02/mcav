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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPalette;

/**
 * Temporally-coherent, strip-parallel Floyd-Steinberg error-diffusion dither. Serpentine
 * scanning (even rows L→R, odd rows R→L) reduces left-biased streaking; the kernel is mirrored
 * on backward rows. Residual error at temporal-skip sites is propagated to avoid banding at
 * static/moving region boundaries.
 */
public final class TemporalFloydSteinbergDither extends TemporalDitherAlgorithm {

  /** Constructs with the default {@link MapPalette} and default thresholds. */
  public TemporalFloydSteinbergDither() {
    super(new MapPalette());
  }

  /**
   * Constructs with the specified palette and default thresholds.
   *
   * @param palette the colour palette to use for quantisation
   */
  public TemporalFloydSteinbergDither(final DitherPalette palette) {
    super(palette);
  }

  /**
   * Constructs with all parameters specified.
   *
   * @param palette            the colour palette to use for quantisation
   * @param temporalThreshold  per-channel tolerance for reusing a previous palette index (≥ 0)
   * @param errorThreshold     minimum total error below which error is not diffused (≥ 0)
   * @param errorStrength      fraction of quantisation error to diffuse, in [0.0, 1.0]
   */
  public TemporalFloydSteinbergDither(
    final DitherPalette palette,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    super(palette, temporalThreshold, errorThreshold, errorStrength);
  }

  /** {@inheritDoc} */
  @Override
  protected void diffuseError(
    final int x,
    final int width,
    final int[] current,
    final int[] next,
    final boolean hasNextY,
    final boolean forward,
    final int dR,
    final int dG,
    final int dB
  ) {
    final int bufIdx = x * 3;
    if (forward) {
      if (x < width - 1) {
        current[bufIdx + 3] += (dR * 7) >> 4;
        current[bufIdx + 4] += (dG * 7) >> 4;
        current[bufIdx + 5] += (dB * 7) >> 4;
      }
      if (hasNextY) {
        if (x > 0) {
          next[bufIdx - 3] += (dR * 3) >> 4;
          next[bufIdx - 2] += (dG * 3) >> 4;
          next[bufIdx - 1] += (dB * 3) >> 4;
        }
        next[bufIdx] += (dR * 5) >> 4;
        next[bufIdx + 1] += (dG * 5) >> 4;
        next[bufIdx + 2] += (dB * 5) >> 4;
        if (x < width - 1) {
          next[bufIdx + 3] += dR >> 4;
          next[bufIdx + 4] += dG >> 4;
          next[bufIdx + 5] += dB >> 4;
        }
      }
    } else {
      if (x > 0) {
        current[bufIdx - 3] += (dR * 7) >> 4;
        current[bufIdx - 2] += (dG * 7) >> 4;
        current[bufIdx - 1] += (dB * 7) >> 4;
      }
      if (hasNextY) {
        if (x < width - 1) {
          next[bufIdx + 3] += (dR * 3) >> 4;
          next[bufIdx + 4] += (dG * 3) >> 4;
          next[bufIdx + 5] += (dB * 3) >> 4;
        }
        next[bufIdx] += (dR * 5) >> 4;
        next[bufIdx + 1] += (dG * 5) >> 4;
        next[bufIdx + 2] += (dB * 5) >> 4;
        if (x > 0) {
          next[bufIdx - 3] += dR >> 4;
          next[bufIdx - 2] += dG >> 4;
          next[bufIdx - 1] += dB >> 4;
        }
      }
    }
  }
}
