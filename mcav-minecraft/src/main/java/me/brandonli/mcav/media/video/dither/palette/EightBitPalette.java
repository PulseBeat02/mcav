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
package me.brandonli.mcav.media.video.dither.palette;

import java.util.List;

/**
 * The EightBitPalette class is a concrete implementation of the ColorPalette class
 * that provides a predefined palette of 8 colors. These colors are represented as
 * 32-bit RGB integer values, allowing the palette to be used in visual media or
 * rendering applications requiring a fixed 8-color set.
 * <p>
 * This class is designed for efficient color mapping and lookup operations,
 * leveraging the functionality of the parent ColorPalette class.
 * <p>
 * By default, the palette contains the following colors:
 * - Red
 * - Cyan
 * - Green
 * - White
 * - Black
 * - Light Gray
 * - Dark Gray
 * - Brown
 */
public final class EightBitPalette extends ColorPalette {

  /**
   * Constructs an EightBitPalette instance with a predefined set of 8 colors.
   * These colors are represented as 32-bit RGB integer values and are included
   * in the palette for various graphics or media applications.
   * <p>
   * The palette is defined as follows:
   * - Red: -65536
   * - Cyan: -16721606
   * - Light Blue: -13158436
   * - White: -1
   * - Black: -16777216
   * - Brown: -1710797
   * - Green: -10691627
   * - Gray: -5092136
   * <p>
   * The EightBitPalette allows for efficient color lookups and mappings using the
   * internally inherited functionality from the ColorPalette superclass.
   */
  public EightBitPalette() {
    super(List.of(-65536, -16721606, -13158436, -1, -16777216, -1710797, -10691627, -5092136));
  }
}
