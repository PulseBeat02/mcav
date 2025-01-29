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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.nearest;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * A concrete implementation of the {@link AbstractDitherAlgorithm} for performing
 * nearest-color dithering. This algorithm processes an image by approximating
 * each pixel to the closest matching color in a specified palette without any error
 * propagation.
 * <p>
 * The {@code NearestDither} class overrides methods to apply dithering logic, working
 * directly on image pixel buffers or converting image data to a byte array format.
 * This implementation is designed for scenarios where simple, non-error diffusing
 * dithering is sufficient.
 * <p>
 * Key Characteristics:
 * - Maps each pixel to the closest available color in the palette based on RGB distance.
 * - Does not introduce additional artifacts due to error propagation.
 * - Designed to operate efficiently on both arrays of pixel data and static images.
 * <p>
 * This class relies on utility methods from {@link DitherUtils} to determine the best
 * matching color in the palette.
 * <p>
 * Thread Safety:
 * Instances of {@code NearestDither} are not thread-safe due to potential shared state
 * within the palette or utility methods. If thread safety is required, use synchronization
 * or create separate instances for concurrent use.
 */
public final class NearestDitherImpl extends AbstractDitherAlgorithm implements NearestDither {

  /**
   * Constructs an instance of the {@code NearestDitherImpl} class using the specified {@code Palette}.
   * This implementation applies a nearest-color dithering algorithm, where each pixel in an image
   * is mapped to the closest matching color in the provided palette.
   *
   * @param palette the {@code Palette} object containing the set of colors to be used for
   *                approximating pixel values during the dithering process.
   */
  public NearestDitherImpl(final Palette palette) {
    super(palette);
  }

  /**
   * Converts the pixel data of the specified {@link StaticImage} into a byte array
   * representation using a nearest-color dithering algorithm.
   * <p>
   * Each pixel of the image is mapped to the closest matching color in the palette,
   * with the resulting colors stored sequentially in the returned byte array.
   * <p>
   * This method processes the image data row by row, calculating the best matching
   * color for each pixel based on its RGB components and the provided palette.
   *
   * @param image the {@link StaticImage} instance containing the pixel data to be processed
   * @return a byte array representing the dithered image, where each byte corresponds
   * to the closest matching color in the palette
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image) {
    final Palette palette = this.getPalette();
    final int width = image.getWidth();
    final int[] buffer = image.getAllPixels();
    final int length = buffer.length;
    final int height = length / width;
    final ByteBuffer data = ByteBuffer.allocate(length);
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int color = buffer[yIndex + x];
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = (color) & 0xFF;
        data.put(DitherUtils.getBestColor(palette, r, g, b));
      }
    }
    return data.array();
  }

  /**
   * Performs dithering on the given pixel buffer using the nearest-color approximation.
   * This method updates each pixel in the buffer by mapping it to the closest matching
   * color in the associated {@link Palette} based on RGB distance.
   * <p>
   * The dithering process does not propagate errors, making it suitable for scenarios
   * where simple and efficient color approximation is sufficient.
   *
   * @param buffer the array of image pixels, where each pixel is represented as an
   *               integer in RGB format (0xRRGGBB)
   * @param width  the width of the image, used to calculate row indices in the buffer
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        final int r = (color >> 16) & 0xFF;
        final int g = (color >> 8) & 0xFF;
        final int b = (color) & 0xFF;
        buffer[index] = DitherUtils.getBestColorNormal(palette, r, g, b);
      }
    }
  }
}
