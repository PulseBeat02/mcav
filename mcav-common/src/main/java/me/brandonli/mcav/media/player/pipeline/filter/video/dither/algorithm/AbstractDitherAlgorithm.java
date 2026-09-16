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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * The base class of dithering algorithms, which holds the palette and validates inputs.
 */
public abstract class AbstractDitherAlgorithm implements DitherAlgorithm {

  private final DitherPalette palette;

  /**
   * Constructs a new algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   */
  protected AbstractDitherAlgorithm(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    this.palette = palette;
  }

  /**
   * Constructs a new algorithm for the Minecraft map palette.
   */
  protected AbstractDitherAlgorithm() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Gets the palette the algorithm reduces images to.
   *
   * @return the palette
   */
  @Override
  public DitherPalette getPalette() {
    return this.palette;
  }

  /**
   * Validates the arguments of {@link #dither(int[], int)}.
   *
   * @param buffer the pixels
   * @param width  the width of the image
   */
  protected static void checkBuffer(final int[] buffer, final int width) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null");
    Preconditions.checkArgument(width > 0, "Width must be positive");
    Preconditions.checkArgument(buffer.length % width == 0, "Buffer length %s is not a multiple of width %s", buffer.length, width);
  }

  /**
   * Validates the argument of {@link #ditherIntoBytes(ImageBuffer)}.
   *
   * @param image the image
   */
  protected static void checkImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
  }
}
