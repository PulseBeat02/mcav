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

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPalette;

/**
 * An abstract base class representing a general implementation of a dithering
 * algorithm.
 */
public abstract class AbstractDitherAlgorithm implements DitherAlgorithm {

  private final DitherPalette palette;

  /**
   * Constructs an instance of the {@code AbstractDitherAlgorithm} class with the given palette.
   *
   * @param palette the {@code Palette} object representing the color palette to be used
   *                during the dithering process. This palette provides the color set
   *                against which pixel colors will be approximated.
   */
  public AbstractDitherAlgorithm(final DitherPalette palette) {
    this.palette = palette;
  }

  /**
   * Constructs an instance of {@code AbstractDitherAlgorithm} using a default color
   * palette.
   */
  public AbstractDitherAlgorithm() {
    this(new MapPalette());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DitherPalette getPalette() {
    return this.palette;
  }
}
