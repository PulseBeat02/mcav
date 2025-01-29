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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ordered;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

/**
 * The OrderedDither class implements the DitherAlgorithm interface and provides functionality
 * to perform ordered dithering on images. Ordered dithering adjusts the colors of an image
 * by applying a threshold matrix to distribute quantization errors in a structured manner.
 * This implementation uses a predefined palette and a pixel mapping matrix to process
 * the input image.
 */
public final class OrderedDither extends AbstractDitherAlgorithm implements BayerDither {

  private final Palette palette;
  private final float[][] precalc;
  private final int xdim;
  private final int ydim;

  /**
   * Constructs an OrderedDither instance with the specified color palette and pixel mapping matrix.
   * The OrderedDither class performs ordered dithering on images by utilizing a predefined palette
   * and a threshold matrix derived from the given PixelMapper.
   *
   * @param palette the color palette to be used for dithering. If null, a default palette is used.
   * @param mapper  the pixel mapper
   */
  public OrderedDither(final Palette palette, final PixelMapper mapper) {
    this.palette = palette == null ? Palette.DEFAULT_MAP_PALETTE : palette;
    this.precalc = mapper.getMatrix();
    this.ydim = this.precalc.length;
    this.xdim = this.precalc[0].length;
  }

  /**
   * Applies ordered dithering to a given image and converts it into a byte array.
   * This method modifies the image pixels using a precomputed dither matrix and
   * a predefined color palette, translating each pixel into a single byte representing
   * the closest matching color from the palette.
   *
   * @param image the input image to be dithered, represented as a StaticImage object.
   *              The image's pixels are retrieved and processed for dithering based
   *              on the provided width and the precomputed dither matrix.
   * @param width the width of the image in pixels. This is used to determine the
   *              dimensions of the image and to correctly apply the dithering pattern row by row.
   * @return a byte array where each byte represents a color index from the palette corresponding
   * to a dithered pixel in the image.
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final int[] buffer = image.getAllPixels();
    final int length = buffer.length;
    final int height = length / width;
    final ByteBuffer data = ByteBuffer.allocate(length);
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (r += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(r, 0);
        g = (g += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(g, 0);
        b = (b += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(b, 0);
        data.put(DitherUtils.getBestColor(this.palette, r, g, b));
      }
    }
    return data.array();
  }

  /**
   * Applies ordered dithering to the given buffer. This method modifies the colors of the pixels
   * in the specified buffer based on a precomputed dither matrix and the provided palette. It adjusts
   * the red, green, and blue channels for each pixel and replaces them with the closest matching color
   * from the palette.
   *
   * @param buffer the array of pixel data represented as integers in ARGB format. The buffer will be
   *               modified in-place to reflect the dithered colors.
   * @param width  the width of the image or buffer in pixels. This determines the number of pixels
   *               per row and is used for properly applying the dither pattern across each row.
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final int height = buffer.length / width;
    for (int y = 0; y < height; y++) {
      final int yIndex = y * width;
      for (int x = 0; x < width; x++) {
        final int index = yIndex + x;
        final int color = buffer[index];
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (r += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(r, 0);
        g = (g += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(g, 0);
        b = (b += (int) this.precalc[y % this.ydim][x % this.xdim]) > 255 ? 255 : Math.max(b, 0);
        buffer[index] = DitherUtils.getBestColorNormal(this.palette, r, g, b);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Palette getPalette() {
    return this.palette;
  }
}
