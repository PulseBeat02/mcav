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
 * StevensonArceDither is a concrete implementation of the error diffusion dithering algorithm
 * that uses the Stevenson-Arce kernel for distributing quantization errors during the dithering
 * process. This dithering approach modifies the color depth of an image by reducing the number of
 * available colors while preserving the visual quality through error propagation.
 * <p>
 * This class extends the ErrorDiffusionDither, leveraging its base functionality for managing
 * palettes and distributing quantization errors within an image. The Stevenson-Arce kernel
 * is specifically designed for achieving visually balanced dithering.
 */
public final class StevensonArceDither extends ErrorDiffusionDither {

  /**
   * Constructs an instance of the {@code StevensonArceDither} class with the specified color
   * palette. This constructor initializes the dithering algorithm by setting up the provided
   * palette, which defines the set of colors that will be used during the dithering process.
   *
   * @param palette the {@code Palette} object representing the color palette to be used
   *                for mapping pixel colors to the nearest available colors during
   *                the Stevenson-Arce dithering process.
   */
  public StevensonArceDither(final Palette palette) {
    super(palette);
  }

  /**
   * Applies dithering to an image buffer using error diffusion. Adjusts the colors
   * of the provided RGB buffer to better fit within a limited palette while minimizing
   * visual distortion.
   *
   * @param buffer the array of pixels represented as integers in ARGB format. Each integer
   *               contains 8 bits per channel for alpha, red, green, and blue.
   * @param width  the width of the image. This is needed to interpret the 1D buffer
   *               as a 2D grid of pixels.
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
            buf1[bufferIndex] = (delta_r * 32) / 200;
            buf1[bufferIndex + 1] = (delta_g * 32) / 200;
            buf1[bufferIndex + 2] = (delta_b * 32) / 200;
          }
          if (hasNextY) {
            if (x > 1) {
              buf2[bufferIndex - 6] = (delta_r * 12) / 200;
              buf2[bufferIndex - 5] = (delta_g * 12) / 200;
              buf2[bufferIndex - 4] = (delta_b * 12) / 200;
            }
            if (x > 0) {
              buf2[bufferIndex - 3] = (delta_r * 26) / 200;
              buf2[bufferIndex - 2] = (delta_g * 26) / 200;
              buf2[bufferIndex - 1] = (delta_b * 26) / 200;
            }
            buf2[bufferIndex] = (delta_r * 30) / 200;
            buf2[bufferIndex + 1] = (delta_g * 30) / 200;
            buf2[bufferIndex + 2] = (delta_b * 30) / 200;
            if (x < widthMinus) {
              buf2[bufferIndex + 3] = (delta_r * 16) / 200;
              buf2[bufferIndex + 4] = (delta_g * 16) / 200;
              buf2[bufferIndex + 5] = (delta_b * 16) / 200;
            }
            if (x < width - 2) {
              buf2[bufferIndex + 6] = (delta_r * 12) / 200;
              buf2[bufferIndex + 7] = (delta_g * 12) / 200;
              buf2[bufferIndex + 8] = (delta_b * 12) / 200;
            }
            if (y < height - 2) {
              if (x > 1) {
                buf3[bufferIndex - 6] = (delta_r * 5) / 200;
                buf3[bufferIndex - 5] = (delta_g * 5) / 200;
                buf3[bufferIndex - 4] = (delta_b * 5) / 200;
              }
              if (x > 0) {
                buf3[bufferIndex - 3] = (delta_r * 12) / 200;
                buf3[bufferIndex - 2] = (delta_g * 12) / 200;
                buf3[bufferIndex - 1] = (delta_b * 12) / 200;
              }
              buf3[bufferIndex] = (delta_r * 26) / 200;
              buf3[bufferIndex + 1] = (delta_g * 26) / 200;
              buf3[bufferIndex + 2] = (delta_b * 26) / 200;
              if (x < widthMinus) {
                buf3[bufferIndex + 3] = (delta_r * 12) / 200;
                buf3[bufferIndex + 4] = (delta_g * 12) / 200;
                buf3[bufferIndex + 5] = (delta_b * 12) / 200;
              }
              if (x < width - 2) {
                buf3[bufferIndex + 6] = (delta_r * 5) / 200;
                buf3[bufferIndex + 7] = (delta_g * 5) / 200;
                buf3[bufferIndex + 8] = (delta_b * 5) / 200;
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
            buf1[bufferIndex] = (delta_b * 32) / 200;
            buf1[bufferIndex - 1] = (delta_g * 32) / 200;
            buf1[bufferIndex - 2] = (delta_r * 32) / 200;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 12) / 200;
              buf2[bufferIndex + 5] = (delta_g * 12) / 200;
              buf2[bufferIndex + 4] = (delta_r * 12) / 200;
            }
            if (x < width - 2) {
              buf2[bufferIndex + 9] = (delta_b * 5) / 200;
              buf2[bufferIndex + 8] = (delta_g * 5) / 200;
              buf2[bufferIndex + 7] = (delta_r * 5) / 200;
            }
            buf2[bufferIndex + 3] = (delta_b * 26) / 200;
            buf2[bufferIndex + 2] = (delta_g * 26) / 200;
            buf2[bufferIndex + 1] = (delta_r * 26) / 200;
            buf2[bufferIndex] = (delta_b * 30) / 200;
            buf2[bufferIndex - 1] = (delta_g * 30) / 200;
            buf2[bufferIndex - 2] = (delta_r * 30) / 200;
            if (x > 0) {
              buf2[bufferIndex - 3] = (delta_b * 16) / 200;
              buf2[bufferIndex - 4] = (delta_g * 16) / 200;
              buf2[bufferIndex - 5] = (delta_r * 16) / 200;
            }
            if (x > 1) {
              buf2[bufferIndex - 6] = (delta_b * 12) / 200;
              buf2[bufferIndex - 7] = (delta_g * 12) / 200;
              buf2[bufferIndex - 8] = (delta_r * 12) / 200;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 12) / 200;
                buf3[bufferIndex + 5] = (delta_g * 12) / 200;
                buf3[bufferIndex + 4] = (delta_r * 12) / 200;
              }
              if (x < width - 2) {
                buf3[bufferIndex + 9] = (delta_b * 5) / 200;
                buf3[bufferIndex + 8] = (delta_g * 5) / 200;
                buf3[bufferIndex + 7] = (delta_r * 5) / 200;
              }
              buf3[bufferIndex + 3] = (delta_b * 26) / 200;
              buf3[bufferIndex + 2] = (delta_g * 26) / 200;
              buf3[bufferIndex + 1] = (delta_r * 26) / 200;
              buf3[bufferIndex] = (delta_b * 30) / 200;
              buf3[bufferIndex - 1] = (delta_g * 30) / 200;
              buf3[bufferIndex - 2] = (delta_r * 30) / 200;
              if (x > 0) {
                buf3[bufferIndex - 3] = (delta_b * 16) / 200;
                buf3[bufferIndex - 4] = (delta_g * 16) / 200;
                buf3[bufferIndex - 5] = (delta_r * 16) / 200;
              }
              if (x > 1) {
                buf3[bufferIndex - 6] = (delta_b * 12) / 200;
                buf3[bufferIndex - 7] = (delta_g * 12) / 200;
                buf3[bufferIndex - 8] = (delta_r * 12) / 200;
              }
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * Applies dithering to the provided static image and converts the result into a byte array.
   * The dithering technique modifies pixel values to simulate higher color depth by distributing
   * pixel errors across adjacent pixels in a determined pattern.
   *
   * @param image the input static image object containing pixel data to be processed
   * @param width the width of the image, which is used to calculate dimensions and indexes
   * @return a byte array representing the dithered image where each byte contains a reduced-color pixel
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getAllPixels();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[3][width + (width << 1)];
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
            buf1[bufferIndex] = (delta_r * 32) / 200;
            buf1[bufferIndex + 1] = (delta_g * 32) / 200;
            buf1[bufferIndex + 2] = (delta_b * 32) / 200;
          }
          if (hasNextY) {
            if (x > 1) {
              buf2[bufferIndex - 6] = (delta_r * 12) / 200;
              buf2[bufferIndex - 5] = (delta_g * 12) / 200;
              buf2[bufferIndex - 4] = (delta_b * 12) / 200;
            }
            if (x > 0) {
              buf2[bufferIndex - 3] = (delta_r * 26) / 200;
              buf2[bufferIndex - 2] = (delta_g * 26) / 200;
              buf2[bufferIndex - 1] = (delta_b * 26) / 200;
            }
            buf2[bufferIndex] = (delta_r * 30) / 200;
            buf2[bufferIndex + 1] = (delta_g * 30) / 200;
            buf2[bufferIndex + 2] = (delta_b * 30) / 200;
            if (x < widthMinus) {
              buf2[bufferIndex + 3] = (delta_r * 16) / 200;
              buf2[bufferIndex + 4] = (delta_g * 16) / 200;
              buf2[bufferIndex + 5] = (delta_b * 16) / 200;
            }
            if (x < width - 2) {
              buf2[bufferIndex + 6] = (delta_r * 12) / 200;
              buf2[bufferIndex + 7] = (delta_g * 12) / 200;
              buf2[bufferIndex + 8] = (delta_b * 12) / 200;
            }
            if (y < height - 2) {
              if (x > 1) {
                buf3[bufferIndex - 6] = (delta_r * 5) / 200;
                buf3[bufferIndex - 5] = (delta_g * 5) / 200;
                buf3[bufferIndex - 4] = (delta_b * 5) / 200;
              }
              if (x > 0) {
                buf3[bufferIndex - 3] = (delta_r * 12) / 200;
                buf3[bufferIndex - 2] = (delta_g * 12) / 200;
                buf3[bufferIndex - 1] = (delta_b * 12) / 200;
              }
              buf3[bufferIndex] = (delta_r * 26) / 200;
              buf3[bufferIndex + 1] = (delta_g * 26) / 200;
              buf3[bufferIndex + 2] = (delta_b * 26) / 200;
              if (x < widthMinus) {
                buf3[bufferIndex + 3] = (delta_r * 12) / 200;
                buf3[bufferIndex + 4] = (delta_g * 12) / 200;
                buf3[bufferIndex + 5] = (delta_b * 12) / 200;
              }
              if (x < width - 2) {
                buf3[bufferIndex + 6] = (delta_r * 5) / 200;
                buf3[bufferIndex + 7] = (delta_g * 5) / 200;
                buf3[bufferIndex + 8] = (delta_b * 5) / 200;
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
            buf1[bufferIndex] = (delta_b * 32) / 200;
            buf1[bufferIndex - 1] = (delta_g * 32) / 200;
            buf1[bufferIndex - 2] = (delta_r * 32) / 200;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 12) / 200;
              buf2[bufferIndex + 5] = (delta_g * 12) / 200;
              buf2[bufferIndex + 4] = (delta_r * 12) / 200;
            }
            if (x < width - 2) {
              buf2[bufferIndex + 9] = (delta_b * 5) / 200;
              buf2[bufferIndex + 8] = (delta_g * 5) / 200;
              buf2[bufferIndex + 7] = (delta_r * 5) / 200;
            }
            buf2[bufferIndex + 3] = (delta_b * 26) / 200;
            buf2[bufferIndex + 2] = (delta_g * 26) / 200;
            buf2[bufferIndex + 1] = (delta_r * 26) / 200;
            buf2[bufferIndex] = (delta_b * 30) / 200;
            buf2[bufferIndex - 1] = (delta_g * 30) / 200;
            buf2[bufferIndex - 2] = (delta_r * 30) / 200;
            if (x > 0) {
              buf2[bufferIndex - 3] = (delta_b * 16) / 200;
              buf2[bufferIndex - 4] = (delta_g * 16) / 200;
              buf2[bufferIndex - 5] = (delta_r * 16) / 200;
            }
            if (x > 1) {
              buf2[bufferIndex - 6] = (delta_b * 12) / 200;
              buf2[bufferIndex - 7] = (delta_g * 12) / 200;
              buf2[bufferIndex - 8] = (delta_r * 12) / 200;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 12) / 200;
                buf3[bufferIndex + 5] = (delta_g * 12) / 200;
                buf3[bufferIndex + 4] = (delta_r * 12) / 200;
              }
              if (x < width - 2) {
                buf3[bufferIndex + 9] = (delta_b * 5) / 200;
                buf3[bufferIndex + 8] = (delta_g * 5) / 200;
                buf3[bufferIndex + 7] = (delta_r * 5) / 200;
              }
              buf3[bufferIndex + 3] = (delta_b * 26) / 200;
              buf3[bufferIndex + 2] = (delta_g * 26) / 200;
              buf3[bufferIndex + 1] = (delta_r * 26) / 200;
              buf3[bufferIndex] = (delta_b * 30) / 200;
              buf3[bufferIndex - 1] = (delta_g * 30) / 200;
              buf3[bufferIndex - 2] = (delta_r * 30) / 200;
              if (x > 0) {
                buf3[bufferIndex - 3] = (delta_b * 16) / 200;
                buf3[bufferIndex - 4] = (delta_g * 16) / 200;
                buf3[bufferIndex - 5] = (delta_r * 16) / 200;
              }
              if (x > 1) {
                buf3[bufferIndex - 6] = (delta_b * 12) / 200;
                buf3[bufferIndex - 7] = (delta_g * 12) / 200;
                buf3[bufferIndex - 8] = (delta_r * 12) / 200;
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
