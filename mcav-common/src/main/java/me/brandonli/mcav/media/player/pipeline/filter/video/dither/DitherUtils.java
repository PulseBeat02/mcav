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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.MapPaletteLoader;

/**
 * Utility class for dithering-related operations.
 */
public final class DitherUtils {

  private DitherUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Retrieves the nearest matching color index from a given palette for a specified RGB color.
   *
   * @param palette the palette containing the color map used for matching
   * @param r       the red component of the color, in the range 0-255
   * @param g       the green component of the color, in the range 0-255
   * @param b       the blue component of the color, in the range 0-255
   * @return the index of the closest matching color in the palette as a byte
   */
  public static byte getBestColor(final DitherPalette palette, final int r, final int g, final int b) {
    final byte[] colors = palette.getColorMap();
    return colors[((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1)];
  }

  /**
   * Determines and retrieves the best full color value from the palette for the given RGB components.
   *
   * @param palette the color palette containing the precomputed full color map
   * @param red     the red component of the desired color (0-255)
   * @param green   the green component of the desired color (0-255)
   * @param blue    the blue component of the desired color (0-255)
   * @return the best matching full color value from the palette, as an integer
   */
  public static int getBestFullColor(final DitherPalette palette, final int red, final int green, final int blue) {
    final int[] colors = palette.getFullColorMap();
    return colors[((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1)];
  }

  /**
   * Determines the best matching color in the given palette for the specified RGB values,
   * and returns its corresponding RGB integer value.
   *
   * @param palette the palette containing the available colors
   * @param r       the red component of the color (0-255)
   * @param g       the green component of the color (0-255)
   * @param b       the blue component of the color (0-255)
   * @return the RGB value of the best matching color from the palette
   */
  public static int getBestColorNormal(final DitherPalette palette, final int r, final int g, final int b) {
    return MapPaletteLoader.getColor(getBestColor(palette, r, g, b)).getRGB();
  }

  /**
   * Retrieves a color from a given Minecraft-compatible palette based on a specified value.
   *
   * @param palette the Palette instance containing the Minecraft-compatible color palette
   * @param val     the byte value used to index into the palette, adjusted within the range [0, 255]
   * @return the color at the adjusted index within the palette as an integer (ARGB format)
   */
  public static int getColorFromMinecraftPalette(final DitherPalette palette, final byte val) {
    final int[] colors = palette.getPalette();
    return colors[(val + 256) % 256];
  }

  /**
   * Determines the best matching color index for a given RGB value, considering transparency.
   *
   * @param palette the palette used to compute the best color index
   * @param rgb     the packed integer RGB value, where the alpha channel is in the highest 8 bits
   * @return the best matching color index as a byte, or 0 if the input RGB value is fully transparent
   */
  public static byte getBestColorIncludingTransparent(final DitherPalette palette, final int rgb) {
    final int r = (rgb >> 16) & 0xFF;
    final int g = (rgb >> 8) & 0xFF;
    final int b = rgb & 0xFF;
    return ((rgb >>> 24) & 0xFF) == 0 ? 0 : getBestColor(palette, r, g, b);
  }

  /**
   * Simplifies an integer RGB buffer into a byte-based representation using a provided color palette.
   *
   * @param palette the color palette to be used for determining the best match for each RGB value
   * @param buffer  an array of integer RGB values to simplify
   * @return a byte array where each element represents the index of the best matching color from the palette
   */
  public static byte[] simplify(final DitherPalette palette, final int[] buffer) {
    final byte[] map = new byte[buffer.length];
    for (int index = 0; index < buffer.length; index++) {
      final int rgb = buffer[index];
      final int red = (rgb >> 16) & 0xFF;
      final int green = (rgb >> 8) & 0xFF;
      final int blue = rgb & 0xFF;
      final byte ptr = DitherUtils.getBestColor(palette, red, green, blue);
      map[index] = ptr;
    }
    return map;
  }
}
