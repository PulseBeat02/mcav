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
package me.brandonli.mcav.media.video.dither.algorithm.error;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.video.dither.DitherUtils;
import me.brandonli.mcav.media.video.dither.palette.Palette;

/**
 * The AtkinsonDither class applies the Atkinson dithering algorithm to reduce
 * the color palette of an image while preserving visual detail by distributing
 * the quantization error to neighboring pixels.
 * <p>
 * Atkinson dithering is a variation of error diffusion dithering that
 * distributes error to the neighboring pixels in a fixed and controlled
 * pattern, resulting in a fine-grained halftone effect. This is particularly
 * useful for reducing colors to a limited palette without introducing banding.
 * <p>
 * This implementation extends the base ErrorDiffusionDither class and operates
 * by processing an image buffer row by row, alternating processing directions
 * between odd and even rows. The method supports both in-place dithering and
 * generating a dithered byte array from an image.
 */
public final class AtkinsonDither extends ErrorDiffusionDither {

  /**
   * Constructs a new instance of the AtkinsonDither algorithm.
   *
   * @param palette the color palette used for the dithering process. This palette provides
   *                the set of colors to map the image's pixels to during the error diffusion
   *                dithering process.
   */
  public AtkinsonDither(final Palette palette) {
    super(palette);
  }

  /**
   * Applies the Atkinson dithering algorithm to a given buffer of pixel data.
   * This algorithm is used to reduce the number of colors in the image while maintaining
   * visual detail by distributing the error generated in pixel color quantization to neighboring pixels.
   *
   * @param buffer the pixel buffer containing the image data to dither. Each pixel is represented
   *               as an integer, typically using 32-bit ARGB encoding (alpha, red, green, blue).
   * @param width  the width of the image in pixels. This determines how the one-dimensional
   *               pixel buffer is divided into rows.
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][width + (width << 1)];
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        for (int x = 0; x < width; x++) {
          final boolean hasNextX = x < widthMinus;
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red += buf1[bufferIndex++]) > 255 ? 255 : Math.max(red, 0);
          green = (green += buf1[bufferIndex++]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue += buf1[bufferIndex++]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX) {
            buf1[bufferIndex] = delta_r >> 3;
            buf1[bufferIndex + 1] = delta_g >> 3;
            buf1[bufferIndex + 2] = delta_b >> 3;
          }
          if (hasNextY) {
            buf2[bufferIndex - 3] = delta_r >> 3;
            buf2[bufferIndex - 2] = delta_g >> 3;
            buf2[bufferIndex - 1] = delta_b >> 3;
            if (x > 0) {
              buf2[bufferIndex - 6] = delta_r >> 3;
              buf2[bufferIndex - 5] = delta_g >> 3;
              buf2[bufferIndex - 4] = delta_b >> 3;
            }
            if (x < widthMinus) {
              buf2[bufferIndex + 3] = delta_r >> 3;
              buf2[bufferIndex + 4] = delta_g >> 3;
              buf2[bufferIndex + 5] = delta_b >> 3;
            }
          }
          buffer[index] = closest;
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        for (int x = width - 1; x >= 0; x--) {
          final boolean hasNextX = x > 0;
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          blue = (blue += buf1[bufferIndex--]) > 255 ? 255 : Math.max(blue, 0);
          green = (green += buf1[bufferIndex--]) > 255 ? 255 : Math.max(green, 0);
          red = (red += buf1[bufferIndex--]) > 255 ? 255 : Math.max(red, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX) {
            buf1[bufferIndex] = delta_b >> 3;
            buf1[bufferIndex - 1] = delta_g >> 3;
            buf1[bufferIndex - 2] = delta_r >> 3;
          }
          if (hasNextY) {
            buf2[bufferIndex + 3] = delta_b >> 3;
            buf2[bufferIndex + 2] = delta_g >> 3;
            buf2[bufferIndex + 1] = delta_r >> 3;
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = delta_b >> 3;
              buf2[bufferIndex + 5] = delta_g >> 3;
              buf2[bufferIndex + 4] = delta_r >> 3;
            }
            if (x > 0) {
              buf2[bufferIndex - 3] = delta_b >> 3;
              buf2[bufferIndex - 2] = delta_g >> 3;
              buf2[bufferIndex - 1] = delta_r >> 3;
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * Converts a static image into a dithered byte array using the Atkinson dithering algorithm.
   *
   * @param image The input static image to be dithered.
   * @param width The width of the input image in pixels.
   * @return A byte array containing the dithered result.
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getAllPixels();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][width + (width << 1)];
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        for (int x = 0; x < width; x++) {
          final boolean hasNextX = x < widthMinus;
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red += buf1[bufferIndex++]) > 255 ? 255 : Math.max(red, 0);
          green = (green += buf1[bufferIndex++]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue += buf1[bufferIndex++]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX) {
            buf1[bufferIndex] = delta_r >> 3;
            buf1[bufferIndex + 1] = delta_g >> 3;
            buf1[bufferIndex + 2] = delta_b >> 3;
          }
          if (hasNextY) {
            buf2[bufferIndex - 3] = delta_r >> 3;
            buf2[bufferIndex - 2] = delta_g >> 3;
            buf2[bufferIndex - 1] = delta_b >> 3;
            if (x > 0) {
              buf2[bufferIndex - 6] = delta_r >> 3;
              buf2[bufferIndex - 5] = delta_g >> 3;
              buf2[bufferIndex - 4] = delta_b >> 3;
            }
            if (x < widthMinus) {
              buf2[bufferIndex + 3] = delta_r >> 3;
              buf2[bufferIndex + 4] = delta_g >> 3;
              buf2[bufferIndex + 5] = delta_b >> 3;
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        for (int x = width - 1; x >= 0; x--) {
          final boolean hasNextX = x > 0;
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          blue = (blue += buf1[bufferIndex--]) > 255 ? 255 : Math.max(blue, 0);
          green = (green += buf1[bufferIndex--]) > 255 ? 255 : Math.max(green, 0);
          red = (red += buf1[bufferIndex--]) > 255 ? 255 : Math.max(red, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX) {
            buf1[bufferIndex] = delta_b >> 3;
            buf1[bufferIndex - 1] = delta_g >> 3;
            buf1[bufferIndex - 2] = delta_r >> 3;
          }
          if (hasNextY) {
            buf2[bufferIndex + 3] = delta_b >> 3;
            buf2[bufferIndex + 2] = delta_g >> 3;
            buf2[bufferIndex + 1] = delta_r >> 3;
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = delta_b >> 3;
              buf2[bufferIndex + 5] = delta_g >> 3;
              buf2[bufferIndex + 4] = delta_r >> 3;
            }
            if (x > 0) {
              buf2[bufferIndex - 3] = delta_b >> 3;
              buf2[bufferIndex - 2] = delta_g >> 3;
              buf2[bufferIndex - 1] = delta_r >> 3;
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      }
    }
    return data.array();
  }
}
