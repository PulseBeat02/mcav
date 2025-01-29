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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;

public final class StevensonArceDither extends ErrorDiffusionDither {

  public StevensonArceDither(final Palette palette) {
    super(palette);
  }

  // < 1% error, huge performance gain
  // (delta_? * 32) / 200  →  (delta_? * 41) >> 8
  // (delta_? * 12) / 200  →  (delta_? * 61) >> 10
  // (delta_? * 26) / 200  →  (delta_? * 33) >> 8
  // (delta_? * 30) / 200  →  (delta_? * 77) >> 9
  // (delta_? * 16) / 200  →  (delta_? * 41) >> 9
  // (delta_? * 5) / 200  →  (delta_? * 51) >> 11

  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[3][(width + 4) << 2];
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
          if (hasNextX && bufferIndex + 2 < buf1.length) {
            buf1[bufferIndex] = (delta_r * 41) >> 8;
            buf1[bufferIndex + 1] = (delta_g * 41) >> 8;
            buf1[bufferIndex + 2] = (delta_b * 41) >> 8;
          }
          if (hasNextY) {
            if (x > 1 && bufferIndex >= 6 && bufferIndex - 6 < buf2.length) {
              buf2[bufferIndex - 6] = (delta_r * 61) >> 10;
              buf2[bufferIndex - 5] = (delta_g * 61) >> 10;
              buf2[bufferIndex - 4] = (delta_b * 61) >> 10;
            }
            if (x > 0 && bufferIndex >= 3 && bufferIndex - 3 < buf2.length) {
              buf2[bufferIndex - 3] = (delta_r * 33) >> 8;
              buf2[bufferIndex - 2] = (delta_g * 33) >> 8;
              buf2[bufferIndex - 1] = (delta_b * 33) >> 8;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] = (delta_r * 77) >> 9;
              buf2[bufferIndex + 1] = (delta_g * 77) >> 9;
              buf2[bufferIndex + 2] = (delta_b * 77) >> 9;
            }
            if (x < widthMinus && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] = (delta_r * 41) >> 9;
              buf2[bufferIndex + 4] = (delta_g * 41) >> 9;
              buf2[bufferIndex + 5] = (delta_b * 41) >> 9;
            }
            if (x < width - 2 && bufferIndex + 8 < buf2.length) {
              buf2[bufferIndex + 6] = (delta_r * 61) >> 10;
              buf2[bufferIndex + 7] = (delta_g * 61) >> 10;
              buf2[bufferIndex + 8] = (delta_b * 61) >> 10;
            }
            if (y < height - 2) {
              if (x > 1 && bufferIndex >= 6 && bufferIndex - 6 < buf3.length) {
                buf3[bufferIndex - 6] = (delta_r * 51) >> 11;
                buf3[bufferIndex - 5] = (delta_g * 51) >> 11;
                buf3[bufferIndex - 4] = (delta_b * 51) >> 11;
              }
              if (x > 0 && bufferIndex >= 3 && bufferIndex - 3 < buf3.length) {
                buf3[bufferIndex - 3] = (delta_r * 61) >> 10;
                buf3[bufferIndex - 2] = (delta_g * 61) >> 10;
                buf3[bufferIndex - 1] = (delta_b * 61) >> 10;
              }
              if (bufferIndex + 2 < buf3.length) {
                buf3[bufferIndex] = (delta_r * 33) >> 8;
                buf3[bufferIndex + 1] = (delta_g * 33) >> 8;
                buf3[bufferIndex + 2] = (delta_b * 33) >> 8;
              }
              if (x < widthMinus && bufferIndex + 5 < buf3.length) {
                buf3[bufferIndex + 3] = (delta_r * 61) >> 10;
                buf3[bufferIndex + 4] = (delta_g * 61) >> 10;
                buf3[bufferIndex + 5] = (delta_b * 61) >> 10;
              }
              if (x < width - 2 && bufferIndex + 8 < buf3.length) {
                buf3[bufferIndex + 6] = (delta_r * 51) >> 11;
                buf3[bufferIndex + 7] = (delta_g * 51) >> 11;
                buf3[bufferIndex + 8] = (delta_b * 51) >> 11;
              }
            }
          }
          buffer[index] = closest;
        }
      } else {
        int bufferIndex = Math.min(width * 3 - 1, ditherBuffer[1].length - 1);
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
          if (bufferIndex >= 2) {
            blue = (blue += buf1[bufferIndex--]) > 255 ? 255 : Math.max(blue, 0);
            green = (green += buf1[bufferIndex--]) > 255 ? 255 : Math.max(green, 0);
            red = (red += buf1[bufferIndex--]) > 255 ? 255 : Math.max(red, 0);
          } else {
            bufferIndex = Math.max(0, bufferIndex - 3);
          }
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX && bufferIndex >= 0 && bufferIndex + 2 < buf1.length) {
            buf1[bufferIndex] = (delta_b * 41) >> 8;
            buf1[bufferIndex - 1] = (delta_g * 41) >> 8;
            buf1[bufferIndex - 2] = (delta_r * 41) >> 8;
          }
          if (hasNextY) {
            if (x < widthMinus && bufferIndex + 6 < buf2.length) {
              buf2[bufferIndex + 6] = (delta_b * 61) >> 10;
              buf2[bufferIndex + 5] = (delta_g * 61) >> 10;
              buf2[bufferIndex + 4] = (delta_r * 61) >> 10;
            }
            if (x < width - 2 && bufferIndex + 9 < buf2.length) {
              buf2[bufferIndex + 9] = (delta_b * 51) >> 11;
              buf2[bufferIndex + 8] = (delta_g * 51) >> 11;
              buf2[bufferIndex + 7] = (delta_r * 51) >> 11;
            }
            if (bufferIndex + 3 < buf2.length) {
              buf2[bufferIndex + 3] = (delta_b * 33) >> 8;
              buf2[bufferIndex + 2] = (delta_g * 33) >> 8;
              buf2[bufferIndex + 1] = (delta_r * 33) >> 8;
            }
            if (bufferIndex >= 2 && bufferIndex < buf2.length) {
              buf2[bufferIndex] = (delta_b * 77) >> 9;
              buf2[bufferIndex - 1] = (delta_g * 77) >> 9;
              buf2[bufferIndex - 2] = (delta_r * 77) >> 9;
            }
            if (x > 0 && bufferIndex >= 5 && bufferIndex - 5 < buf2.length) {
              buf2[bufferIndex - 3] = (delta_b * 41) >> 9;
              buf2[bufferIndex - 4] = (delta_g * 41) >> 9;
              buf2[bufferIndex - 5] = (delta_r * 41) >> 9;
            }
            if (x > 1 && bufferIndex >= 8 && bufferIndex - 8 < buf2.length) {
              buf2[bufferIndex - 6] = (delta_b * 61) >> 10;
              buf2[bufferIndex - 7] = (delta_g * 61) >> 10;
              buf2[bufferIndex - 8] = (delta_r * 61) >> 10;
            }
            if (y < height - 2) {
              if (x < widthMinus && bufferIndex + 6 < buf3.length) {
                buf3[bufferIndex + 6] = (delta_b * 61) >> 10;
                buf3[bufferIndex + 5] = (delta_g * 61) >> 10;
                buf3[bufferIndex + 4] = (delta_r * 61) >> 10;
              }
              if (x < width - 2 && bufferIndex + 9 < buf3.length) {
                buf3[bufferIndex + 9] = (delta_b * 51) >> 11;
                buf3[bufferIndex + 8] = (delta_g * 51) >> 11;
                buf3[bufferIndex + 7] = (delta_r * 51) >> 11;
              }
              if (bufferIndex + 3 < buf3.length) {
                buf3[bufferIndex + 3] = (delta_b * 33) >> 8;
                buf3[bufferIndex + 2] = (delta_g * 33) >> 8;
                buf3[bufferIndex + 1] = (delta_r * 33) >> 8;
              }
              if (bufferIndex >= 2 && bufferIndex < buf3.length) {
                buf3[bufferIndex] = (delta_b * 77) >> 9;
                buf3[bufferIndex - 1] = (delta_g * 77) >> 9;
                buf3[bufferIndex - 2] = (delta_r * 77) >> 9;
              }
              if (x > 0 && bufferIndex >= 5 && bufferIndex - 5 < buf3.length) {
                buf3[bufferIndex - 3] = (delta_b * 41) >> 9;
                buf3[bufferIndex - 4] = (delta_g * 41) >> 9;
                buf3[bufferIndex - 5] = (delta_r * 41) >> 9;
              }
              if (x > 1 && bufferIndex >= 8 && bufferIndex - 8 < buf3.length) {
                buf3[bufferIndex - 6] = (delta_b * 61) >> 10;
                buf3[bufferIndex - 7] = (delta_g * 61) >> 10;
                buf3[bufferIndex - 8] = (delta_r * 61) >> 10;
              }
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
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[3][(width + 4) << 2];
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
          if (hasNextX && bufferIndex + 2 < buf1.length) {
            buf1[bufferIndex] = (delta_r * 41) >> 8;
            buf1[bufferIndex + 1] = (delta_g * 41) >> 8;
            buf1[bufferIndex + 2] = (delta_b * 41) >> 8;
          }
          if (hasNextY) {
            if (x > 1 && bufferIndex >= 6 && bufferIndex - 6 < buf2.length) {
              buf2[bufferIndex - 6] = (delta_r * 61) >> 10;
              buf2[bufferIndex - 5] = (delta_g * 61) >> 10;
              buf2[bufferIndex - 4] = (delta_b * 61) >> 10;
            }
            if (x > 0 && bufferIndex >= 3 && bufferIndex - 3 < buf2.length) {
              buf2[bufferIndex - 3] = (delta_r * 33) >> 8;
              buf2[bufferIndex - 2] = (delta_g * 33) >> 8;
              buf2[bufferIndex - 1] = (delta_b * 33) >> 8;
            }
            if (bufferIndex + 2 < buf2.length) {
              buf2[bufferIndex] = (delta_r * 77) >> 9;
              buf2[bufferIndex + 1] = (delta_g * 77) >> 9;
              buf2[bufferIndex + 2] = (delta_b * 77) >> 9;
            }
            if (x < widthMinus && bufferIndex + 5 < buf2.length) {
              buf2[bufferIndex + 3] = (delta_r * 41) >> 9;
              buf2[bufferIndex + 4] = (delta_g * 41) >> 9;
              buf2[bufferIndex + 5] = (delta_b * 41) >> 9;
            }
            if (x < width - 2 && bufferIndex + 8 < buf2.length) {
              buf2[bufferIndex + 6] = (delta_r * 61) >> 10;
              buf2[bufferIndex + 7] = (delta_g * 61) >> 10;
              buf2[bufferIndex + 8] = (delta_b * 61) >> 10;
            }
            if (y < height - 2) {
              if (x > 1 && bufferIndex >= 6 && bufferIndex - 6 < buf3.length) {
                buf3[bufferIndex - 6] = (delta_r * 51) >> 11;
                buf3[bufferIndex - 5] = (delta_g * 51) >> 11;
                buf3[bufferIndex - 4] = (delta_b * 51) >> 11;
              }
              if (x > 0 && bufferIndex >= 3 && bufferIndex - 3 < buf3.length) {
                buf3[bufferIndex - 3] = (delta_r * 61) >> 10;
                buf3[bufferIndex - 2] = (delta_g * 61) >> 10;
                buf3[bufferIndex - 1] = (delta_b * 61) >> 10;
              }
              if (bufferIndex + 2 < buf3.length) {
                buf3[bufferIndex] = (delta_r * 33) >> 8;
                buf3[bufferIndex + 1] = (delta_g * 33) >> 8;
                buf3[bufferIndex + 2] = (delta_b * 33) >> 8;
              }
              if (x < widthMinus && bufferIndex + 5 < buf3.length) {
                buf3[bufferIndex + 3] = (delta_r * 61) >> 10;
                buf3[bufferIndex + 4] = (delta_g * 61) >> 10;
                buf3[bufferIndex + 5] = (delta_b * 61) >> 10;
              }
              if (x < width - 2 && bufferIndex + 8 < buf3.length) {
                buf3[bufferIndex + 6] = (delta_r * 51) >> 11;
                buf3[bufferIndex + 7] = (delta_g * 51) >> 11;
                buf3[bufferIndex + 8] = (delta_b * 51) >> 11;
              }
            }
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        int bufferIndex = Math.min(width * 3 - 1, ditherBuffer[1].length - 1);
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
          if (bufferIndex >= 2) {
            blue = (blue += buf1[bufferIndex--]) > 255 ? 255 : Math.max(blue, 0);
            green = (green += buf1[bufferIndex--]) > 255 ? 255 : Math.max(green, 0);
            red = (red += buf1[bufferIndex--]) > 255 ? 255 : Math.max(red, 0);
          } else {
            bufferIndex = Math.max(0, bufferIndex - 3);
          }
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          if (hasNextX && bufferIndex >= 0 && bufferIndex + 2 < buf1.length) {
            buf1[bufferIndex] = (delta_b * 41) >> 8;
            buf1[bufferIndex - 1] = (delta_g * 41) >> 8;
            buf1[bufferIndex - 2] = (delta_r * 41) >> 8;
          }
          if (hasNextY) {
            if (x < widthMinus && bufferIndex + 6 < buf2.length) {
              buf2[bufferIndex + 6] = (delta_b * 61) >> 10;
              buf2[bufferIndex + 5] = (delta_g * 61) >> 10;
              buf2[bufferIndex + 4] = (delta_r * 61) >> 10;
            }
            if (x < width - 2 && bufferIndex + 9 < buf2.length) {
              buf2[bufferIndex + 9] = (delta_b * 51) >> 11;
              buf2[bufferIndex + 8] = (delta_g * 51) >> 11;
              buf2[bufferIndex + 7] = (delta_r * 51) >> 11;
            }
            if (bufferIndex + 3 < buf2.length) {
              buf2[bufferIndex + 3] = (delta_b * 33) >> 8;
              buf2[bufferIndex + 2] = (delta_g * 33) >> 8;
              buf2[bufferIndex + 1] = (delta_r * 33) >> 8;
            }
            if (bufferIndex >= 2 && bufferIndex < buf2.length) {
              buf2[bufferIndex] = (delta_b * 77) >> 9;
              buf2[bufferIndex - 1] = (delta_g * 77) >> 9;
              buf2[bufferIndex - 2] = (delta_r * 77) >> 9;
            }
            if (x > 0 && bufferIndex >= 5 && bufferIndex - 5 < buf2.length) {
              buf2[bufferIndex - 3] = (delta_b * 41) >> 9;
              buf2[bufferIndex - 4] = (delta_g * 41) >> 9;
              buf2[bufferIndex - 5] = (delta_r * 41) >> 9;
            }
            if (x > 1 && bufferIndex >= 8 && bufferIndex - 8 < buf2.length) {
              buf2[bufferIndex - 6] = (delta_b * 61) >> 10;
              buf2[bufferIndex - 7] = (delta_g * 61) >> 10;
              buf2[bufferIndex - 8] = (delta_r * 61) >> 10;
            }
            if (y < height - 2) {
              if (x < widthMinus && bufferIndex + 6 < buf3.length) {
                buf3[bufferIndex + 6] = (delta_b * 61) >> 10;
                buf3[bufferIndex + 5] = (delta_g * 61) >> 10;
                buf3[bufferIndex + 4] = (delta_r * 61) >> 10;
              }
              if (x < width - 2 && bufferIndex + 9 < buf3.length) {
                buf3[bufferIndex + 9] = (delta_b * 51) >> 11;
                buf3[bufferIndex + 8] = (delta_g * 51) >> 11;
                buf3[bufferIndex + 7] = (delta_r * 51) >> 11;
              }
              if (bufferIndex + 3 < buf3.length) {
                buf3[bufferIndex + 3] = (delta_b * 33) >> 8;
                buf3[bufferIndex + 2] = (delta_g * 33) >> 8;
                buf3[bufferIndex + 1] = (delta_r * 33) >> 8;
              }
              if (bufferIndex >= 2 && bufferIndex < buf3.length) {
                buf3[bufferIndex] = (delta_b * 77) >> 9;
                buf3[bufferIndex - 1] = (delta_g * 77) >> 9;
                buf3[bufferIndex - 2] = (delta_r * 77) >> 9;
              }
              if (x > 0 && bufferIndex >= 5 && bufferIndex - 5 < buf3.length) {
                buf3[bufferIndex - 3] = (delta_b * 41) >> 9;
                buf3[bufferIndex - 4] = (delta_g * 41) >> 9;
                buf3[bufferIndex - 5] = (delta_r * 41) >> 9;
              }
              if (x > 1 && bufferIndex >= 8 && bufferIndex - 8 < buf3.length) {
                buf3[bufferIndex - 6] = (delta_b * 61) >> 10;
                buf3[bufferIndex - 7] = (delta_g * 61) >> 10;
                buf3[bufferIndex - 8] = (delta_r * 61) >> 10;
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
