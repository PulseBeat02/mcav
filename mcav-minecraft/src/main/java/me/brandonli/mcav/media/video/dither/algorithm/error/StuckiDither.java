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
 * The StuckiDither class is an implementation of the error diffusion dithering algorithm
 * based on the Stucki kernel. It extends the ErrorDiffusionDither class and provides
 * functionality to dither images by diffusing quantization errors to neighboring
 * pixels in a visually appealing manner.
 * <p>
 * The Stucki dithering approach uses a specific error distribution pattern that
 * balances efficiency and visual quality, often resulting in smooth gradients
 * and reduced visual artifacts. This class supports the conversion of an image
 * to a reduced color representation using the provided color palette.
 * <p>
 * The implementation relies on a predefined palette to map image colors to
 * nearest available matches, and the dithering process is conducted with
 * respect to the width of the target image.
 * <p>
 * This class is suitable for applications where high-quality error diffusion
 * dithering is required, such as video processing and image compression.
 * <p>
 * The class provides methods for direct dithering into a pixel buffer as well
 * as for converting images into byte representations suitable for further processing.
 */
public final class StuckiDither extends ErrorDiffusionDither {

  /**
   * Constructs an instance of the {@code StuckiDither} class with the specified color
   * palette. This constructor initializes the dithering algorithm to use the Stucki
   * error diffusion method, which spreads quantization errors over a larger area
   * to achieve smoother gradients and reduced visual artifacts. It utilizes the given
   * color palette to determine the restricted set of colors for dithering operations.
   *
   * @param palette the {@code Palette} object representing the set of colors to be used
   *                during the Stucki dithering process. This palette defines the restricted
   *                color space to which image pixels will be mapped.
   */
  public StuckiDither(final Palette palette) {
    super(palette);
  }

  /**
   * Applies the Stucki dithering algorithm to the provided pixel buffer. This method modifies
   * the input buffer in place to reduce the color depth while attempting to maintain visual fidelity.
   *
   * @param buffer an array of integers representing the pixel data as ARGB values. The method
   *               modifies this buffer in place to apply the dithering effect.
   * @param width  the width of the image represented by the buffer. Used to calculate the height
   *               and the position of pixels during processing.
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[3][(width + width) << 1];
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        final int[] buf3 = ditherBuffer[2];
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
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          if (hasNextX) {
            buf1[bufferIndex] = (delta_r * 8) / 42;
            buf1[bufferIndex + 1] = (delta_g * 8) / 42;
            buf1[bufferIndex + 2] = (delta_b * 8) / 42;
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r * 4) / 42;
              buf2[bufferIndex - 5] = (delta_g * 4) / 42;
              buf2[bufferIndex - 4] = (delta_b * 4) / 42;
            }
            buf2[bufferIndex - 3] = (delta_r * 8) / 42;
            buf2[bufferIndex - 2] = (delta_g * 8) / 42;
            buf2[bufferIndex - 1] = (delta_b * 8) / 42;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r * 4) / 42;
              buf2[bufferIndex + 1] = (delta_g * 4) / 42;
              buf2[bufferIndex + 2] = (delta_b * 4) / 42;
            }
            if (y < height - 2) {
              if (x > 0) {
                buf3[bufferIndex - 6] = (delta_r * 2) / 42;
                buf3[bufferIndex - 5] = (delta_g * 2) / 42;
                buf3[bufferIndex - 4] = (delta_b * 2) / 42;
              }
              buf3[bufferIndex - 3] = (delta_r * 4) / 42;
              buf3[bufferIndex - 2] = (delta_g * 4) / 42;
              buf3[bufferIndex - 1] = (delta_b * 4) / 42;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_r * 2) / 42;
                buf3[bufferIndex + 1] = (delta_g * 2) / 42;
                buf3[bufferIndex + 2] = (delta_b * 2) / 42;
              }
            }
          }
          buffer[index] = closest;
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        final int[] buf3 = ditherBuffer[2];
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
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          if (hasNextX) {
            buf1[bufferIndex] = (delta_b * 8) / 42;
            buf1[bufferIndex - 1] = (delta_g * 8) / 42;
            buf1[bufferIndex - 2] = (delta_r * 8) / 42;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 4) / 42;
              buf2[bufferIndex + 5] = (delta_g * 4) / 42;
              buf2[bufferIndex + 4] = (delta_r * 4) / 42;
            }
            buf2[bufferIndex + 3] = (delta_b * 8) / 42;
            buf2[bufferIndex + 2] = (delta_g * 8) / 42;
            buf2[bufferIndex + 1] = (delta_r * 8) / 42;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b * 4) / 42;
              buf2[bufferIndex - 1] = (delta_g * 4) / 42;
              buf2[bufferIndex - 2] = (delta_r * 4) / 42;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 2) / 42;
                buf3[bufferIndex + 5] = (delta_g * 2) / 42;
                buf3[bufferIndex + 4] = (delta_r * 2) / 42;
              }
              buf3[bufferIndex + 3] = (delta_b * 4) / 42;
              buf3[bufferIndex + 2] = (delta_g * 4) / 42;
              buf3[bufferIndex + 1] = (delta_r * 4) / 42;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_b * 2) / 42;
                buf3[bufferIndex - 1] = (delta_g * 2) / 42;
                buf3[bufferIndex - 2] = (delta_r * 2) / 42;
              }
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * Converts and dithers the pixels of the provided static image into a byte array
   * using a predefined color palette and dithering algorithm.
   *
   * @param image The static image to be processed. Each pixel is represented as an integer.
   * @param width The width of the image in pixels.
   * @return A byte array where each byte represents a color index corresponding to the nearest match
   * in the palette for the dithered pixel.
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getAllPixels();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[3][(width + width) << 1];
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        final int[] buf3 = ditherBuffer[2];
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
            buf1[bufferIndex] = (delta_r * 8) / 42;
            buf1[bufferIndex + 1] = (delta_g * 8) / 42;
            buf1[bufferIndex + 2] = (delta_b * 8) / 42;
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r * 4) / 42;
              buf2[bufferIndex - 5] = (delta_g * 4) / 42;
              buf2[bufferIndex - 4] = (delta_b * 4) / 42;
            }
            buf2[bufferIndex - 3] = (delta_r * 8) / 42;
            buf2[bufferIndex - 2] = (delta_g * 8) / 42;
            buf2[bufferIndex - 1] = (delta_b * 8) / 42;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r * 4) / 42;
              buf2[bufferIndex + 1] = (delta_g * 4) / 42;
              buf2[bufferIndex + 2] = (delta_b * 4) / 42;
            }
            if (y < height - 2) {
              if (x > 0) {
                buf3[bufferIndex - 6] = (delta_r * 2) / 42;
                buf3[bufferIndex - 5] = (delta_g * 2) / 42;
                buf3[bufferIndex - 4] = (delta_b * 2) / 42;
              }
              buf3[bufferIndex - 3] = (delta_r * 4) / 42;
              buf3[bufferIndex - 2] = (delta_g * 4) / 42;
              buf3[bufferIndex - 1] = (delta_b * 4) / 42;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_r * 2) / 42;
                buf3[bufferIndex + 1] = (delta_g * 2) / 42;
                buf3[bufferIndex + 2] = (delta_b * 2) / 42;
              }
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        final int[] buf3 = ditherBuffer[2];
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
            buf1[bufferIndex] = (delta_b * 8) / 42;
            buf1[bufferIndex - 1] = (delta_g * 8) / 42;
            buf1[bufferIndex - 2] = (delta_r * 8) / 42;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 4) / 42;
              buf2[bufferIndex + 5] = (delta_g * 4) / 42;
              buf2[bufferIndex + 4] = (delta_r * 4) / 42;
            }
            buf2[bufferIndex + 3] = (delta_b * 8) / 42;
            buf2[bufferIndex + 2] = (delta_g * 8) / 42;
            buf2[bufferIndex + 1] = (delta_r * 8) / 42;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b * 4) / 42;
              buf2[bufferIndex - 1] = (delta_g * 4) / 42;
              buf2[bufferIndex - 2] = (delta_r * 4) / 42;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 2) / 42;
                buf3[bufferIndex + 5] = (delta_g * 2) / 42;
                buf3[bufferIndex + 4] = (delta_r * 2) / 42;
              }
              buf3[bufferIndex + 3] = (delta_b * 4) / 42;
              buf3[bufferIndex + 2] = (delta_g * 4) / 42;
              buf3[bufferIndex + 1] = (delta_r * 4) / 42;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_b * 2) / 42;
                buf3[bufferIndex - 1] = (delta_g * 2) / 42;
                buf3[bufferIndex - 2] = (delta_r * 2) / 42;
              }
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      }
    }
    return data.array();
  }
}
