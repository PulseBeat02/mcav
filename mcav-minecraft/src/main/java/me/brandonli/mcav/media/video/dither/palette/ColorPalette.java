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
package me.brandonli.mcav.media.video.dither.palette;

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.video.dither.load.LoadRed;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * The ColorPalette class is an implementation of the Palette interface used to
 * manage and process a set of colors, generating lookup tables for efficient
 * color mapping operations. This class is primarily designed for scenarios
 * involving color reduction or remapping in dithered or indexed color applications.
 * <p>
 * Each instance of ColorPalette is constructed from a predefined list of colors
 * which is used to initialize the internal palette, a color mapping table, and a full
 * color mapping table. These data structures support efficient retrieval of colors
 * and index mappings at runtime.
 * <p>
 * The construction process involves:
 * 1. Initializing the palette with the specified list of colors.
 * 2. Creating multitasked lookup tables for red, green, and blue channels using
 * parallel processing via ForkJoin framework.
 */
public class ColorPalette implements Palette {

  private final int[] palette;
  private final byte[] colorMap;
  private final int[] fullColorMap;

  /**
   * Constructs a ColorPalette instance initialized with the specified list of colors.
   * This constructor prepares an internal palette, a color mapping table, and a full
   * color mapping table to allow for efficient color lookup and mapping operations.
   *
   * @param colors a list of integers representing the colors to be included in the palette.
   *               Each integer should represent an RGB color encoded as a single value.
   */
  public ColorPalette(final List<Integer> colors) {
    this.palette = new int[colors.size()];
    this.colorMap = new byte[128 * 128 * 128];
    this.fullColorMap = new int[128 * 128 * 128];
    this.updateIndices(colors, this.palette);
    this.createLookupTable(this.forkRed(this.palette), this.palette, this.colorMap, this.fullColorMap);
  }

  private void createLookupTable(
    @UnderInitialization ColorPalette this,
    final List<LoadRed> tasks,
    final int[] palette,
    final byte[] colorMap,
    final int[] fullColorMap
  ) {
    for (int i = 0; i < 128; i++) {
      final byte[] sub = tasks.get(i).join();
      final int ci = i << 14;
      for (int si = 0; si < 16384; si++) {
        colorMap[ci + si] = sub[si];
        fullColorMap[ci + si] = palette[Byte.toUnsignedInt(sub[si])];
      }
    }
  }

  private List<LoadRed> forkRed(@UnderInitialization ColorPalette this, final int[] palette) {
    final List<LoadRed> tasks = new ArrayList<>(128);
    for (int r = 0; r < 256; r += 2) {
      final LoadRed red = new LoadRed(palette, r);
      tasks.add(red);
      red.fork();
    }
    return tasks;
  }

  private void updateIndices(@UnderInitialization ColorPalette this, final List<Integer> colors, final int[] palette) {
    int index = 0;
    for (final int color : colors) {
      palette[index++] = color;
    }
    palette[0] = 0;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] getPalette() {
    return this.palette;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getColorMap() {
    return this.colorMap;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] getFullColorMap() {
    return this.fullColorMap;
  }
}
