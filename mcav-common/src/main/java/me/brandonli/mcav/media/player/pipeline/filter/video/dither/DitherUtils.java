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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

/**
 * Fast palette lookups shared by the dithering algorithms. All lookups are constant time table reads, and every
 * method is safe to call from any thread.
 *
 * <p>{@link #getLookupIndex(int, int, int)} and {@link #clamp(int)} run for every channel of every pixel, so they
 * leave their arguments unchecked; the dithering algorithms only pass them values they have already clamped. The
 * other methods check their arguments.
 */
public final class DitherUtils {

  private static final int CHANNEL_MASK = 0xFF;

  private DitherUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Computes the index into the palette lookup tables for an RGB color. The components are not checked, because
   * this method runs for every pixel; values outside the range from 0 to 255 produce an index outside the tables.
   *
   * @param red   the red component from 0 to 255
   * @param green the green component from 0 to 255
   * @param blue  the blue component from 0 to 255
   * @return the table index
   */
  public static int getLookupIndex(final int red, final int green, final int blue) {
    return ((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1);
  }

  /**
   * Finds the palette index of the color closest to an RGB color.
   *
   * @param palette the palette
   * @param red     the red component from 0 to 255
   * @param green   the green component from 0 to 255
   * @param blue    the blue component from 0 to 255
   * @return the palette index
   * @throws IllegalArgumentException if a component is outside the range from 0 to 255
   */
  public static byte getBestColor(final DitherPalette palette, final int red, final int green, final int blue) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    checkChannels(red, green, blue);

    final byte[] colorMap = palette.getColorMap();
    final int index = getLookupIndex(red, green, blue);
    return colorMap[index];
  }

  /**
   * Finds the palette color closest to an RGB color.
   *
   * @param palette the palette
   * @param red     the red component from 0 to 255
   * @param green   the green component from 0 to 255
   * @param blue    the blue component from 0 to 255
   * @return the closest palette color as opaque ARGB
   * @throws IllegalArgumentException if a component is outside the range from 0 to 255
   */
  public static int getBestFullColor(final DitherPalette palette, final int red, final int green, final int blue) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    checkChannels(red, green, blue);

    final int[] fullColorMap = palette.getFullColorMap();
    final int index = getLookupIndex(red, green, blue);
    return fullColorMap[index];
  }

  private static void checkChannels(final int red, final int green, final int blue) {
    final int combined = red | green | blue;
    final boolean inRange = (combined & ~CHANNEL_MASK) == 0;
    Preconditions.checkArgument(inRange, "Color components must be between 0 and 255, got %s, %s and %s", red, green, blue);
  }

  /**
   * Finds the palette color closest to an RGB color. This method is an alias of
   * {@link #getBestFullColor(DitherPalette, int, int, int)}.
   *
   * @param palette the palette
   * @param red     the red component from 0 to 255
   * @param green   the green component from 0 to 255
   * @param blue    the blue component from 0 to 255
   * @return the closest palette color as opaque ARGB
   * @throws IllegalArgumentException if a component is outside the range from 0 to 255
   */
  public static int getBestColorNormal(final DitherPalette palette, final int red, final int green, final int blue) {
    return getBestFullColor(palette, red, green, blue);
  }

  /**
   * Gets the color of a palette index.
   *
   * @param palette the palette
   * @param index   the palette index, as stored in map data; negative bytes are treated as unsigned
   * @return the color as opaque ARGB, or zero for a reserved transparent index
   * @throws IndexOutOfBoundsException if the palette has no color at the index
   */
  public static int getColorFromMinecraftPalette(final DitherPalette palette, final byte index) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    final int[] colors = palette.getPalette();
    final int unsigned = index & CHANNEL_MASK;
    Preconditions.checkElementIndex(unsigned, colors.length, "Palette index");
    return colors[unsigned];
  }

  /**
   * Finds the palette index of the color closest to an ARGB color, mapping fully transparent colors to the first
   * reserved index of the palette.
   *
   * @param palette the palette
   * @param argb    the color
   * @return the palette index, or zero if the color is fully transparent
   */
  public static byte getBestColorIncludingTransparent(final DitherPalette palette, final int argb) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    final int alpha = argb >>> 24;
    if (alpha == 0) {
      return 0;
    }

    final int red = (argb >> 16) & CHANNEL_MASK;
    final int green = (argb >> 8) & CHANNEL_MASK;
    final int blue = argb & CHANNEL_MASK;
    return getBestColor(palette, red, green, blue);
  }

  /**
   * Maps every pixel to its closest palette index without dithering.
   *
   * @param palette the palette
   * @param pixels  the pixels as ARGB
   * @return the palette index of every pixel
   */
  public static byte[] simplify(final DitherPalette palette, final int[] pixels) {
    Preconditions.checkNotNull(palette, "Palette must not be null");
    Preconditions.checkNotNull(pixels, "Pixels must not be null");

    final byte[] colorMap = palette.getColorMap();
    final byte[] indices = new byte[pixels.length];
    for (int pixelIndex = 0; pixelIndex < pixels.length; pixelIndex++) {
      final int argb = pixels[pixelIndex];
      final int red = (argb >> 16) & CHANNEL_MASK;
      final int green = (argb >> 8) & CHANNEL_MASK;
      final int blue = argb & CHANNEL_MASK;
      final int lookup = getLookupIndex(red, green, blue);
      indices[pixelIndex] = colorMap[lookup];
    }
    return indices;
  }

  /**
   * Clamps a channel value to the range 0 to 255. This method runs for every channel of every pixel, and accepts
   * any value.
   *
   * @param value the value
   * @return the clamped value
   */
  public static int clamp(final int value) {
    if ((value & ~0xFF) == 0) {
      return value;
    }
    return value < 0 ? 0 : 255;
  }
}
