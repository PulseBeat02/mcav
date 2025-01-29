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

import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.video.dither.DitherUtils;
import me.brandonli.mcav.media.video.dither.palette.Palette;
import me.brandonli.mcav.utils.NativeUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;

import java.nio.ByteBuffer;

public final class FilterLiteDither extends ErrorDiffusionDither {

  private static boolean NATIVE_SUPPORTED;

  static {
    NATIVE_SUPPORTED = false;
    final OS os = OSUtils.getOS();
    final Arch arch = OSUtils.getArch();
    final Bits bits = OSUtils.getBits();
    String libraryName = null;
    if (os == OS.LINUX) {
      if (arch == Arch.X86 && bits == Bits.BITS_64) {
        libraryName = "libfilterlite-linux-x86_64.so";
      } else if (arch == Arch.ARM) {
        if (bits == Bits.BITS_32) {
          libraryName = "libfilterlite-linux-armhf.so";
        } else if (bits == Bits.BITS_64) {
          libraryName = "libfilterlite-linux-aarch64.so";
        }
      }
    } else if (os == OS.WINDOWS) {
      if (bits == Bits.BITS_32) {
        libraryName = "filterlite-win32.dll";
      } else if (bits == Bits.BITS_64) {
        libraryName = "filterlite-win64.dll";
      }
    }
    if (libraryName != null) {
      NativeUtils.loadLibrary(libraryName);
      NATIVE_SUPPORTED = true;
    }
  }

  public FilterLiteDither(final Palette palette) {
    super(palette);
  }

  /**
   * Performs Filter Lite Dithering at a more optimized pace while giving similar results to Floyd
   * Steinberg Dithering.
   *
   * @author PulseBeat_02
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    final Palette palette = this.getPalette();
    final int height = buffer.length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][width << 2];

    /*

    Simple Sierra 2-4A Dithering (Filter Lite)


        *  2
    1  1       (1/4)

    When Jagged Matrix is Multiplied:

          *  2/4
    1/4 1/4

     */

    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        for (int x = 0; x < width; ++x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex++];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex++];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex++];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          final int xMask = -((x < widthMinus) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex + 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex + 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int prevXMask = -((x > 0) ? 1 : 0);
            buf2[bufferIndex - 6] = (delta_r >> 2) & prevXMask;
            buf2[bufferIndex - 5] = (delta_g >> 2) & prevXMask;
            buf2[bufferIndex - 4] = (delta_b >> 2) & prevXMask;
            buf2[bufferIndex - 3] = delta_r >> 2;
            buf2[bufferIndex - 2] = delta_g >> 2;
            buf2[bufferIndex - 1] = delta_b >> 2;
          }
          buffer[index] = closest;
        }
      } else {
        int bufferIndex = (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        for (int x = width - 1; x >= 0; --x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex--];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex--];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex--];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int delta_r = red - ((closest >> 16) & 0xFF);
          final int delta_g = green - ((closest >> 8) & 0xFF);
          final int delta_b = blue - (closest & 0xFF);
          final int xMask = -((x > 0) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex - 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex - 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int nextXMask = -((x < widthMinus) ? 1 : 0);
            buf2[bufferIndex + 6] = (delta_r >> 2) & nextXMask;
            buf2[bufferIndex + 5] = (delta_g >> 2) & nextXMask;
            buf2[bufferIndex + 4] = (delta_b >> 2) & nextXMask;
            buf2[bufferIndex + 3] = delta_r >> 2;
            buf2[bufferIndex + 2] = delta_g >> 2;
            buf2[bufferIndex + 1] = delta_b >> 2;
          }
          buffer[index] = closest;
        }
      }
    }
  }

  public native byte[] ditherNatively(int[] buffer, int width, int[] colors, byte[] mapColors);

  public byte[] ditherExample(final int[] buffer, final int width, final int[] colors, final byte[] mapColors) {
    final int length = buffer.length;
    final int height = length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][width << 2];
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        for (int x = 0; x < width; ++x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex++];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex++];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex++];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = colors[((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1)];
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          final int xMask = -((x < widthMinus) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex + 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex + 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int prevXMask = -((x > 0) ? 1 : 0);
            buf2[bufferIndex - 6] = (delta_r >> 2) & prevXMask;
            buf2[bufferIndex - 5] = (delta_g >> 2) & prevXMask;
            buf2[bufferIndex - 4] = (delta_b >> 2) & prevXMask;
            buf2[bufferIndex - 3] = delta_r >> 2;
            buf2[bufferIndex - 2] = delta_g >> 2;
            buf2[bufferIndex - 1] = delta_b >> 2;
          }
          data.put(index, mapColors[((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1)]);
        }
      } else {
        int bufferIndex = (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        for (int x = width - 1; x >= 0; --x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex--];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex--];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex--];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = colors[((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1)];
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          final int xMask = -((x > 0) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex - 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex - 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int nextXMask = -((x < widthMinus) ? 1 : 0);
            buf2[bufferIndex + 6] = (delta_r >> 2) & nextXMask;
            buf2[bufferIndex + 5] = (delta_g >> 2) & nextXMask;
            buf2[bufferIndex + 4] = (delta_b >> 2) & nextXMask;
            buf2[bufferIndex + 3] = delta_r >> 2;
            buf2[bufferIndex + 2] = delta_g >> 2;
            buf2[bufferIndex + 1] = delta_b >> 2;
          }
          data.put(index, mapColors[((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1)]);
        }
      }
    }
    return data.array();
  }

  @Override
  public byte[] ditherIntoBytes(final StaticImage image, final int width) {
    final Palette palette = this.getPalette();
    if (NATIVE_SUPPORTED) {
      final int[] colors = palette.getFullColorMap();
      final byte[] mapColors = palette.getColorMap();
      return this.ditherNatively(image.getAllPixels(), width, colors, mapColors);
    }
    final int[] buffer = image.getAllPixels();
    final int length = buffer.length;
    final int height = length / width;
    final int widthMinus = width - 1;
    final int heightMinus = height - 1;
    final int[][] ditherBuffer = new int[2][width << 2];
    final ByteBuffer data = ByteBuffer.allocate(buffer.length);
    for (int y = 0; y < height; y++) {
      final boolean hasNextY = y < heightMinus;
      final int yIndex = y * width;
      if ((y & 0x1) == 0) {
        int bufferIndex = 0;
        final int[] buf1 = ditherBuffer[0];
        final int[] buf2 = ditherBuffer[1];
        for (int x = 0; x < width; ++x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex++];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex++];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex++];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          final int xMask = -((x < widthMinus) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex + 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex + 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int prevXMask = -((x > 0) ? 1 : 0);
            buf2[bufferIndex - 6] = (delta_r >> 2) & prevXMask;
            buf2[bufferIndex - 5] = (delta_g >> 2) & prevXMask;
            buf2[bufferIndex - 4] = (delta_b >> 2) & prevXMask;
            buf2[bufferIndex - 3] = delta_r >> 2;
            buf2[bufferIndex - 2] = delta_g >> 2;
            buf2[bufferIndex - 1] = delta_b >> 2;
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      } else {
        int bufferIndex = (width << 1) - 1;
        final int[] buf1 = ditherBuffer[1];
        final int[] buf2 = ditherBuffer[0];
        for (int x = width - 1; x >= 0; --x) {
          final int index = yIndex + x;
          final int rgb = buffer[index];
          int red = (rgb >> 16) & 0xFF;
          int green = (rgb >> 8) & 0xFF;
          int blue = rgb & 0xFF;
          red = red + buf1[bufferIndex--];
          red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
          green = green + buf1[bufferIndex--];
          green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
          blue = blue + buf1[bufferIndex--];
          blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
          final int closest = DitherUtils.getBestFullColor(palette, red, green, blue);
          final int r = (closest >> 16) & 0xFF;
          final int g = (closest >> 8) & 0xFF;
          final int b = closest & 0xFF;
          final int delta_r = red - r;
          final int delta_g = green - g;
          final int delta_b = blue - b;
          final int xMask = -((x > 0) ? 1 : 0);
          buf1[bufferIndex] = (delta_r >> 1) & xMask;
          buf1[bufferIndex - 1] = (delta_g >> 1) & xMask;
          buf1[bufferIndex - 2] = (delta_b >> 1) & xMask;
          if (hasNextY) {
            final int nextXMask = -((x < widthMinus) ? 1 : 0);
            buf2[bufferIndex + 6] = (delta_r >> 2) & nextXMask;
            buf2[bufferIndex + 5] = (delta_g >> 2) & nextXMask;
            buf2[bufferIndex + 4] = (delta_b >> 2) & nextXMask;
            buf2[bufferIndex + 3] = delta_r >> 2;
            buf2[bufferIndex + 2] = delta_g >> 2;
            buf2[bufferIndex + 1] = delta_b >> 2;
          }
          data.put(index, DitherUtils.getBestColor(palette, r, g, b));
        }
      }
    }
    return data.array();
  }
}
