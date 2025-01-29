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
import java.util.Arrays;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

public final class AtkinsonDither extends ErrorDiffusionDither {

  public AtkinsonDither(final Palette palette) {
    super(palette);
  }

  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int[][] ditherBuffer = new int[3][width * 3];
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < height - 1;
      final int yIndex = y * width;
      if (hasNextY) {
        Arrays.fill(ditherBuffer[(y + 1) % 3], 0);
      }
      if (y < height - 2) {
        Arrays.fill(ditherBuffer[(y + 2) % 3], 0);
      }
      final int[] buf1 = ditherBuffer[y % 3];
      final int[] buf2 = ditherBuffer[(y + 1) % 3];
      final int[] buf3 = ditherBuffer[(y + 2) % 3];
      if ((y & 0x1) == 0) {
        for (int x = 0; x < width; x++) {
          final boolean hasNextX = x < widthMinus;
          final int index = yIndex + x;
          final int bufferIndex = x * 3;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red + buf1[bufferIndex]) > 255 ? 255 : Math.max(red, 0);
          green = (green + buf1[bufferIndex + 1]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue + buf1[bufferIndex + 2]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = (red - r) >> 3;
          final int delta_g = (green - g) >> 3;
          final int delta_b = (blue - b) >> 3;
          if (hasNextX && bufferIndex + 5 < buf1.length) {
            buf1[bufferIndex + 3] += delta_r;
            buf1[bufferIndex + 4] += delta_g;
            buf1[bufferIndex + 5] += delta_b;
            if (x < width - 2 && bufferIndex + 8 < buf1.length) {
              buf1[bufferIndex + 6] += delta_r;
              buf1[bufferIndex + 7] += delta_g;
              buf1[bufferIndex + 8] += delta_b;
            }
          }
          if (hasNextY) {
            if (x > 0 && bufferIndex - 1 >= 0) {
              buf2[bufferIndex - 3] += delta_r;
              buf2[bufferIndex - 2] += delta_g;
              buf2[bufferIndex - 1] += delta_b;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] += delta_r;
              buf2[bufferIndex + 1] += delta_g;
              buf2[bufferIndex + 2] += delta_b;
            }
            if (hasNextX && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] += delta_r;
              buf2[bufferIndex + 4] += delta_g;
              buf2[bufferIndex + 5] += delta_b;
            }
            if (y < height - 2 && bufferIndex + 2 < buf3.length) {
              buf3[bufferIndex] += delta_r;
              buf3[bufferIndex + 1] += delta_g;
              buf3[bufferIndex + 2] += delta_b;
            }
          }
          buffer[index] = closest;
        }
      } else {
        for (int x = width - 1; x >= 0; x--) {
          final boolean hasNextX = x > 0;
          final int index = yIndex + x;
          final int bufferIndex = x * 3;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red + buf1[bufferIndex]) > 255 ? 255 : Math.max(red, 0);
          green = (green + buf1[bufferIndex + 1]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue + buf1[bufferIndex + 2]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = (red - r) >> 3;
          final int delta_g = (green - g) >> 3;
          final int delta_b = (blue - b) >> 3;
          if (hasNextX && bufferIndex - 1 >= 0) {
            buf1[bufferIndex - 3] += delta_r;
            buf1[bufferIndex - 2] += delta_g;
            buf1[bufferIndex - 1] += delta_b;
            if (x > 1 && bufferIndex - 4 >= 0) {
              buf1[bufferIndex - 6] += delta_r;
              buf1[bufferIndex - 5] += delta_g;
              buf1[bufferIndex - 4] += delta_b;
            }
          }
          if (hasNextY) {
            if (x < widthMinus && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] += delta_r;
              buf2[bufferIndex + 4] += delta_g;
              buf2[bufferIndex + 5] += delta_b;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] += delta_r;
              buf2[bufferIndex + 1] += delta_g;
              buf2[bufferIndex + 2] += delta_b;
            }
            if (hasNextX && bufferIndex - 1 >= 0) {
              buf2[bufferIndex - 3] += delta_r;
              buf2[bufferIndex - 2] += delta_g;
              buf2[bufferIndex - 1] += delta_b;
            }
            if (y < height - 2 && bufferIndex + 2 < buf3.length) {
              buf3[bufferIndex] += delta_r;
              buf3[bufferIndex + 1] += delta_g;
              buf3[bufferIndex + 2] += delta_b;
            }
          }
          buffer[index] = closest;
        }
      }
    }
  }

  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    final Palette palette = this.getPalette();
    final int[] buffer = image.getPixels();
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int widthMinus = width - 1;
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);
    final int[][] ditherBuffer = new int[3][width * 3];
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < height - 1;
      final int yIndex = y * width;
      if (hasNextY) {
        Arrays.fill(ditherBuffer[(y + 1) % 3], 0);
      }
      if (y < height - 2) {
        Arrays.fill(ditherBuffer[(y + 2) % 3], 0);
      }
      final int[] buf1 = ditherBuffer[y % 3];
      final int[] buf2 = ditherBuffer[(y + 1) % 3];
      final int[] buf3 = ditherBuffer[(y + 2) % 3];
      if ((y & 0x1) == 0) {
        for (int x = 0; x < width; x++) {
          final boolean hasNextX = x < widthMinus;
          final int index = yIndex + x;
          final int bufferIndex = x * 3;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red + buf1[bufferIndex]) > 255 ? 255 : Math.max(red, 0);
          green = (green + buf1[bufferIndex + 1]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue + buf1[bufferIndex + 2]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = (red - r) >> 3;
          final int delta_g = (green - g) >> 3;
          final int delta_b = (blue - b) >> 3;
          if (hasNextX && bufferIndex + 5 < buf1.length) {
            buf1[bufferIndex + 3] += delta_r;
            buf1[bufferIndex + 4] += delta_g;
            buf1[bufferIndex + 5] += delta_b;
            if (x < width - 2 && bufferIndex + 8 < buf1.length) {
              buf1[bufferIndex + 6] += delta_r;
              buf1[bufferIndex + 7] += delta_g;
              buf1[bufferIndex + 8] += delta_b;
            }
          }
          if (hasNextY) {
            if (x > 0 && bufferIndex - 1 >= 0) {
              buf2[bufferIndex - 3] += delta_r;
              buf2[bufferIndex - 2] += delta_g;
              buf2[bufferIndex - 1] += delta_b;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] += delta_r;
              buf2[bufferIndex + 1] += delta_g;
              buf2[bufferIndex + 2] += delta_b;
            }
            if (hasNextX && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] += delta_r;
              buf2[bufferIndex + 4] += delta_g;
              buf2[bufferIndex + 5] += delta_b;
            }
            if (y < height - 2 && bufferIndex + 2 < buf3.length) {
              buf3[bufferIndex] += delta_r;
              buf3[bufferIndex + 1] += delta_g;
              buf3[bufferIndex + 2] += delta_b;
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        for (int x = width - 1; x >= 0; x--) {
          final boolean hasNextX = x > 0;
          final int index = yIndex + x;
          final int bufferIndex = x * 3;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = (red + buf1[bufferIndex]) > 255 ? 255 : Math.max(red, 0);
          green = (green + buf1[bufferIndex + 1]) > 255 ? 255 : Math.max(green, 0);
          blue = (blue + buf1[bufferIndex + 2]) > 255 ? 255 : Math.max(blue, 0);
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = (red - r) >> 3;
          final int delta_g = (green - g) >> 3;
          final int delta_b = (blue - b) >> 3;
          if (hasNextX && bufferIndex - 1 >= 0) {
            buf1[bufferIndex - 3] += delta_r;
            buf1[bufferIndex - 2] += delta_g;
            buf1[bufferIndex - 1] += delta_b;
            if (x > 1 && bufferIndex - 4 >= 0) {
              buf1[bufferIndex - 6] += delta_r;
              buf1[bufferIndex - 5] += delta_g;
              buf1[bufferIndex - 4] += delta_b;
            }
          }
          if (hasNextY) {
            if (x < widthMinus && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] += delta_r;
              buf2[bufferIndex + 4] += delta_g;
              buf2[bufferIndex + 5] += delta_b;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] += delta_r;
              buf2[bufferIndex + 1] += delta_g;
              buf2[bufferIndex + 2] += delta_b;
            }
            if (hasNextX && bufferIndex - 1 >= 0) {
              buf2[bufferIndex - 3] += delta_r;
              buf2[bufferIndex - 2] += delta_g;
              buf2[bufferIndex - 1] += delta_b;
            }
            if (y < height - 2 && bufferIndex + 2 < buf3.length) {
              buf3[bufferIndex] += delta_r;
              buf3[bufferIndex + 1] += delta_g;
              buf3[bufferIndex + 2] += delta_b;
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      }
    }
    return data.array();
  }
}
