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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Implements the Jarvis, Judice, and Ninke dithering algorithm.
 */
public final class JarvisJudiceNinkeDither extends ErrorDiffusionDither {

  /**
   * Creates a new instance of the Jarvis, Judice, and Ninke dithering algorithm.
   * @param palette the palette to use for dithering
   */
  public JarvisJudiceNinkeDither(final DitherPalette palette) {
    super(palette);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final DitherPalette palette = this.getPalette();
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
          buffer[index] = closest;
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    final DitherPalette palette = this.getPalette();
    final int width = image.getWidth();
    final int[] buffer = image.getPixels();
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
