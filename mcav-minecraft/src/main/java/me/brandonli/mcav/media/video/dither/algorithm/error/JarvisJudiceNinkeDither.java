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
 * The {@code JarvisJudiceNinkeDither} class implements the Jarvis-Judice-Ninke
 * error diffusion dithering algorithm. This algorithm is a specific type of error
 * diffusion process that is used to reduce the color depth of an image while
 * preserving visual quality by distributing the quantization error of each pixel
 * to its neighboring pixels according to a predefined diffusion kernel.
 * <p>
 * The Jarvis-Judice-Ninke algorithm distributes the error over a larger area
 * compared to simpler algorithms such as Floyd-Steinberg, which leads to smoother
 * gradients and better overall image quality when reducing colors.
 * <p>
 * This class extends {@link ErrorDiffusionDither} and relies on a specified
 * {@link Palette} for mapping colors to a limited set of predefined values. The
 * algorithm adjusts pixel values and propagates the error in a specific
 * distribution pattern to surrounding pixels, following the kernel design of
 * Jarvis-Judice-Ninke.
 */
public final class JarvisJudiceNinkeDither extends ErrorDiffusionDither {

  /**
   * Constructs an instance of the {@code JarvisJudiceNinkeDither} class with the specified color
   * palette. This constructor initializes the dithering algorithm using the provided palette,
   * which defines the set of colors to be used for mapping pixel values during the dithering
   * process.
   *
   * @param palette the {@code Palette} object representing the color space to be used by the
   *                Jarvis-Judice-Ninke dithering algorithm. This specifies the restricted set
   *                of colors that image pixels will be quantized to.
   */
  public JarvisJudiceNinkeDither(final Palette palette) {
    super(palette);
  }

  /**
   * Applies the Jarvis-Judice-Ninke error diffusion dithering algorithm to an image buffer.
   *
   * @param buffer the array of pixel data in RGB format to be dithered
   * @param width  the width of the image represented by the buffer
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] dither_buffer = new int[3][(width + width) << 1];
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = dither_buffer[0];
        final int[] buf2 = dither_buffer[1];
        final int[] buf3 = dither_buffer[2];
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
            buf1[bufferIndex] = (delta_r * 7) / 48;
            buf1[bufferIndex + 1] = (delta_g * 7) / 48;
            buf1[bufferIndex + 2] = (delta_b * 7) / 48;
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r * 5) / 48;
              buf2[bufferIndex - 5] = (delta_g * 5) / 48;
              buf2[bufferIndex - 4] = (delta_b * 5) / 48;
            }
            buf2[bufferIndex - 3] = (delta_r * 7) / 48;
            buf2[bufferIndex - 2] = (delta_g * 7) / 48;
            buf2[bufferIndex - 1] = (delta_b * 7) / 48;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r * 5) / 48;
              buf2[bufferIndex + 1] = (delta_g * 5) / 48;
              buf2[bufferIndex + 2] = (delta_b * 5) / 48;
            }
            if (y < height - 2) {
              if (x > 0) {
                buf3[bufferIndex - 6] = (delta_r * 3) / 48;
                buf3[bufferIndex - 5] = (delta_g * 3) / 48;
                buf3[bufferIndex - 4] = (delta_b * 3) / 48;
              }
              buf3[bufferIndex - 3] = (delta_r * 5) / 48;
              buf3[bufferIndex - 2] = (delta_g * 5) / 48;
              buf3[bufferIndex - 1] = (delta_b * 5) / 48;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_r * 3) / 48;
                buf3[bufferIndex + 1] = (delta_g * 3) / 48;
                buf3[bufferIndex + 2] = (delta_b * 3) / 48;
              }
            }
          }
          buffer[index] = closest;
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = dither_buffer[1];
        final int[] buf2 = dither_buffer[0];
        final int[] buf3 = dither_buffer[2];
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
            buf1[bufferIndex] = (delta_b * 7) / 48;
            buf1[bufferIndex - 1] = (delta_g * 7) / 48;
            buf1[bufferIndex - 2] = (delta_r * 7) / 48;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 5) / 48;
              buf2[bufferIndex + 5] = (delta_g * 5) / 48;
              buf2[bufferIndex + 4] = (delta_r * 5) / 48;
            }
            buf2[bufferIndex + 3] = (delta_b * 7) / 48;
            buf2[bufferIndex + 2] = (delta_g * 7) / 48;
            buf2[bufferIndex + 1] = (delta_r * 7) / 48;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b * 5) / 48;
              buf2[bufferIndex - 1] = (delta_g * 5) / 48;
              buf2[bufferIndex - 2] = (delta_r * 5) / 48;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 3) / 48;
                buf3[bufferIndex + 5] = (delta_g * 3) / 48;
                buf3[bufferIndex + 4] = (delta_r * 3) / 48;
              }
              buf3[bufferIndex + 3] = (delta_b * 5) / 48;
              buf3[bufferIndex + 2] = (delta_g * 5) / 48;
              buf3[bufferIndex + 1] = (delta_r * 5) / 48;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_b * 3) / 48;
                buf3[bufferIndex - 1] = (delta_g * 3) / 48;
                buf3[bufferIndex - 2] = (delta_r * 3) / 48;
              }
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * Processes the given static image using a dithering algorithm and converts it into a byte array representation.
   *
   * @param image the static image to be processed
   * @param width the width of the image in pixels
   * @return the processed byte array generated from the dithered image
   */
  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getAllPixels();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] dither_buffer = new int[3][(width + width) << 1];
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);

    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = dither_buffer[0];
        final int[] buf2 = dither_buffer[1];
        final int[] buf3 = dither_buffer[2];
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
            buf1[bufferIndex] = (delta_r * 7) / 48;
            buf1[bufferIndex + 1] = (delta_g * 7) / 48;
            buf1[bufferIndex + 2] = (delta_b * 7) / 48;
          }
          if (hasNextY) {
            if (x > 0) {
              buf2[bufferIndex - 6] = (delta_r * 5) / 48;
              buf2[bufferIndex - 5] = (delta_g * 5) / 48;
              buf2[bufferIndex - 4] = (delta_b * 5) / 48;
            }
            buf2[bufferIndex - 3] = (delta_r * 7) / 48;
            buf2[bufferIndex - 2] = (delta_g * 7) / 48;
            buf2[bufferIndex - 1] = (delta_b * 7) / 48;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_r * 5) / 48;
              buf2[bufferIndex + 1] = (delta_g * 5) / 48;
              buf2[bufferIndex + 2] = (delta_b * 5) / 48;
            }
            if (y < height - 2) {
              if (x > 0) {
                buf3[bufferIndex - 6] = (delta_r * 3) / 48;
                buf3[bufferIndex - 5] = (delta_g * 3) / 48;
                buf3[bufferIndex - 4] = (delta_b * 3) / 48;
              }
              buf3[bufferIndex - 3] = (delta_r * 5) / 48;
              buf3[bufferIndex - 2] = (delta_g * 5) / 48;
              buf3[bufferIndex - 1] = (delta_b * 5) / 48;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_r * 3) / 48;
                buf3[bufferIndex + 1] = (delta_g * 3) / 48;
                buf3[bufferIndex + 2] = (delta_b * 3) / 48;
              }
            }
          }

          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        int bufferIndex = width + (width << 1) - 1;
        final int[] buf1 = dither_buffer[1];
        final int[] buf2 = dither_buffer[0];
        final int[] buf3 = dither_buffer[2];
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
            buf1[bufferIndex] = (delta_b * 7) / 48;
            buf1[bufferIndex - 1] = (delta_g * 7) / 48;
            buf1[bufferIndex - 2] = (delta_r * 7) / 48;
          }
          if (hasNextY) {
            if (x < widthMinus) {
              buf2[bufferIndex + 6] = (delta_b * 5) / 48;
              buf2[bufferIndex + 5] = (delta_g * 5) / 48;
              buf2[bufferIndex + 4] = (delta_r * 5) / 48;
            }
            buf2[bufferIndex + 3] = (delta_b * 7) / 48;
            buf2[bufferIndex + 2] = (delta_g * 7) / 48;
            buf2[bufferIndex + 1] = (delta_r * 7) / 48;
            if (hasNextX) {
              buf2[bufferIndex] = (delta_b * 5) / 48;
              buf2[bufferIndex - 1] = (delta_g * 5) / 48;
              buf2[bufferIndex - 2] = (delta_r * 5) / 48;
            }
            if (y < height - 2) {
              if (x < widthMinus) {
                buf3[bufferIndex + 6] = (delta_b * 3) / 48;
                buf3[bufferIndex + 5] = (delta_g * 3) / 48;
                buf3[bufferIndex + 4] = (delta_r * 3) / 48;
              }
              buf3[bufferIndex + 3] = (delta_b * 5) / 48;
              buf3[bufferIndex + 2] = (delta_g * 5) / 48;
              buf3[bufferIndex + 1] = (delta_r * 5) / 48;
              if (hasNextX) {
                buf3[bufferIndex] = (delta_b * 3) / 48;
                buf3[bufferIndex - 1] = (delta_g * 3) / 48;
                buf3[bufferIndex - 2] = (delta_r * 3) / 48;
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
