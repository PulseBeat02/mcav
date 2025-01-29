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
 * The BurkesDither class implements the Burkes error diffusion dithering algorithm
 * by extending the {@code ErrorDiffusionDither} class. This algorithm applies
 * error diffusion to reduce color depth while maintaining visual quality by distributing
 * quantization errors to neighboring pixels in a weighted manner. It is suitable
 * for applications like video dithering or image processing that require color quantization.
 */
public final class BurkesDither extends ErrorDiffusionDither {

  /**
   * Constructs a new instance of the BurkesDither dithering algorithm using the specified color palette.
   * This algorithm applies the Burkes error diffusion technique to reduce the color depth of an image
   * while maintaining visual quality. The palette provided will define the available colors to map the
   * dithered image.
   *
   * @param palette the Palette instance defining the set of colors to be used for dithering. It provides
   *                methods to access the available colors and perform efficient color lookup during the
   *                dithering process.
   */
  public BurkesDither(final Palette palette) {
    super(palette);
  }

  /**
   * Applies dithering to an image represented by a one-dimensional integer array (buffer),
   * modifying the pixel colors to approximate the original with a limited color palette.
   * This method uses the Burkes error diffusion algorithm to distribute the quantization
   * error of each pixel to its neighboring pixels.
   *
   * @param buffer a one-dimensional integer array representing an image, where each element
   *               contains ARGB pixel data. The array is modified in place.
   * @param width  the width of the image being processed, used to calculate the height and
   *               index positions in the buffer.
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][(width + width) << 1];
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
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          if (hasNextX) {
            buf1[bufferIndex] = (delta_r << 3) >> 5; // 8/32
            buf1[bufferIndex + 1] = (delta_g << 3) >> 5; // 8/32
            buf1[bufferIndex + 2] = (delta_b << 3) >> 5; // 8/32
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r << 2) >> 5; // 4/32
              buf2[bufferIndex - 5] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex - 4] = (delta_b << 2) >> 5; // 4/32
            }
            buf2[bufferIndex - 3] = (delta_r << 3) >> 5; // 8/32
            buf2[bufferIndex - 2] = (delta_g << 3) >> 5; // 8/32
            buf2[bufferIndex - 1] = (delta_b << 3) >> 5; // 8/32
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r << 2) >> 5; // 4/32
              buf2[bufferIndex + 1] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex + 2] = (delta_b << 2) >> 5; // 4/32
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
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          if (hasNextX) {
            buf1[bufferIndex] = (delta_b << 3) >> 5; // 8/32
            buf1[bufferIndex - 1] = (delta_g << 3) >> 5; // 8/32
            buf1[bufferIndex - 2] = (delta_r << 3) >> 5; // 8/32
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b << 3) >> 5; // 8/32
              buf2[bufferIndex + 5] = (delta_g << 3) >> 5; // 8/32
              buf2[bufferIndex + 4] = (delta_r << 3) >> 5; // 8/32
            }
            buf2[bufferIndex + 3] = (delta_b << 3) >> 5; // 8/32
            buf2[bufferIndex + 2] = (delta_g << 3) >> 5; // 8/32
            buf2[bufferIndex + 1] = (delta_r << 3) >> 5; // 8/32
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b << 2) >> 5; // 4/32
              buf2[bufferIndex - 1] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex - 2] = (delta_r << 2) >> 5; // 4/32
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * Applies Burkes dithering to the given image and converts it into a byte array.
   *
   * @param image the static image to be dithered
   * @param width the width of the image
   * @return a byte array representing the dithered image
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getAllPixels();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][(width + width) << 1];
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
            buf1[bufferIndex] = (delta_r << 3) >> 5; // 8/32
            buf1[bufferIndex + 1] = (delta_g << 3) >> 5; // 8/32
            buf1[bufferIndex + 2] = (delta_b << 3) >> 5; // 8/32
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r << 2) >> 5; // 4/32
              buf2[bufferIndex - 5] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex - 4] = (delta_b << 2) >> 5; // 4/32
            }
            buf2[bufferIndex - 3] = (delta_r << 3) >> 5; // 8/32
            buf2[bufferIndex - 2] = (delta_g << 3) >> 5; // 8/32
            buf2[bufferIndex - 1] = (delta_b << 3) >> 5; // 8/32
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r << 2) >> 5; // 4/32
              buf2[bufferIndex + 1] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex + 2] = (delta_b << 2) >> 5; // 4/32
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
            buf1[bufferIndex] = (delta_b << 3) >> 5; // 8/32
            buf1[bufferIndex - 1] = (delta_g << 3) >> 5; // 8/32
            buf1[bufferIndex - 2] = (delta_r << 3) >> 5; // 8/32
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b << 3) >> 5; // 8/32
              buf2[bufferIndex + 5] = (delta_g << 3) >> 5; // 8/32
              buf2[bufferIndex + 4] = (delta_r << 3) >> 5; // 8/32
            }
            buf2[bufferIndex + 3] = (delta_b << 3) >> 5; // 8/32
            buf2[bufferIndex + 2] = (delta_g << 3) >> 5; // 8/32
            buf2[bufferIndex + 1] = (delta_r << 3) >> 5; // 8/32
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b << 2) >> 5; // 4/32
              buf2[bufferIndex - 1] = (delta_g << 2) >> 5; // 4/32
              buf2[bufferIndex - 2] = (delta_r << 2) >> 5; // 4/32
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      }
    }
    return data.array();
  }
}
