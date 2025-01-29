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
package me.brandonli.mcav.media.video.dither.algorithm;

import me.brandonli.mcav.media.video.dither.palette.MapPalette;
import me.brandonli.mcav.media.video.dither.palette.Palette;

/**
 * An abstract base class representing a general implementation of a dithering
 * algorithm. The purpose of this class is to provide common functionality
 * and properties for all dithering algorithms, while allowing specific
 * implementations to define their own dithering logic.
 * <p>
 * Concrete subclasses of this class should implement the actual dithering logic
 * by providing implementations for methods defined in the {@link DitherAlgorithm}
 * interface, such as converting image buffers to byte arrays or modifying
 * pixel data directly.
 * <p>
 * The class provides built-in support for managing a color palette, which
 * is essential for mapping image colors to a predefined set of color values
 * during the dithering process.
 */
public abstract class AbstractDitherAlgorithm implements DitherAlgorithm {

  private final Palette palette;

  /**
   * Constructs an instance of the {@code AbstractDitherAlgorithm} class with the given palette.
   * This constructor sets up the palette to be used by the dithering algorithm.
   *
   * @param palette the {@code Palette} object representing the color palette to be used
   *                during the dithering process. This palette provides the color set
   *                against which pixel colors will be approximated.
   */
  public AbstractDitherAlgorithm(final Palette palette) {
    this.palette = palette;
  }

  /**
   * Constructs an instance of {@code AbstractDitherAlgorithm} using a default color
   * palette. The default palette is predefined and provides a set of colors commonly
   * used in scenarios where no custom palette is specified.
   * <p>
   * This constructor delegates to the parameterized constructor, passing an instance
   * of {@code DefaultPalette} as the palette to be used by the dithering algorithm.
   * <p>
   * This base class is intended for extending and provides foundational functionality
   * for implementing specific dithering algorithms.
   */
  public AbstractDitherAlgorithm() {
    this(new MapPalette());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Palette getPalette() {
    return this.palette;
  }
}
