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
 *
 * <p>The base class keeps no mutable state; each subclass states its own thread safety.
 *
 * @since 1.0.0
 */
public abstract class AbstractDitherAlgorithm implements DitherAlgorithm {

  private final DitherPalette palette;

  /**
   * Constructs a new algorithm for the specified palette.
   *
   * @param palette the palette to reduce images to
   * @throws NullPointerException if {@code palette} is null
   * @since 1.0.0
   */
  protected AbstractDitherAlgorithm(final DitherPalette palette) {
    Preconditions.checkNotNull(palette, "Palette must not be null!");

    this.palette = palette;
  }

  /**
   * Constructs a new algorithm for the Minecraft map palette.
   *
   * @since 1.0.0
   */
  protected AbstractDitherAlgorithm() {
    this(DitherPalette.DEFAULT_MAP_PALETTE);
  }

  /**
   * Validates the arguments of {@link #dither(int[], int)}.
   *
   * @param buffer the pixels
   * @param width  the width of the image
   * @throws IllegalArgumentException if width is not positive or does not divide the buffer length
   * @throws NullPointerException     if {@code buffer} is null
   * @since 1.0.0
   */
  protected static void checkBuffer(final int[] buffer, final int width) {
    Preconditions.checkNotNull(buffer, "Buffer must not be null!");
    Preconditions.checkArgument(width > 0, "Width must be positive!");
    Preconditions.checkArgument(
      buffer.length % width == 0,
      "Buffer length %s must be a multiple of the width %s of a row!",
      buffer.length,
      width
    );
  }

  /**
   * Validates the argument of {@link #ditherIntoBytes(ImageBuffer)}.
   *
   * @param image the image
   * @throws NullPointerException if {@code image} is null
   * @since 1.0.0
   */
  protected static void checkImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null!");
  }

  /**
   * Returns the palette this algorithm was built with.
   *
   * @return the palette
   * @since 1.0.0
   */
  @Override
  public DitherPalette getPalette() {
    return this.palette;
  }
}
