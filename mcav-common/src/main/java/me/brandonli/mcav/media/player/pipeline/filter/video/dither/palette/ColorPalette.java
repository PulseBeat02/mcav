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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * A default implementation of the {@link DitherPalette} interface,
 */
public class ColorPalette implements DitherPalette {

  private final int[] palette;
  private final byte[] colorMap;
  private final int[] fullColorMap;

  ColorPalette(final List<Integer> colors) {
    this.palette = new int[colors.size()];
    this.colorMap = new byte[128 * 128 * 128];
    this.fullColorMap = new int[128 * 128 * 128];
    this.updateIndices(colors, this.palette);
    this.createLookupTable(this.forkRed(this.palette), this.palette, this.colorMap, this.fullColorMap);
  }

  ColorPalette(final int... colors) {
    final List<Integer> colorList = new ArrayList<>(colors.length);
    for (final int color : colors) {
      colorList.add(color);
    }
    this.palette = new int[colorList.size()];
    this.colorMap = new byte[128 * 128 * 128];
    this.fullColorMap = new int[128 * 128 * 128];
    this.updateIndices(colorList, this.palette);
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
        final int unsigned = Byte.toUnsignedInt(sub[si]);
        colorMap[ci + si] = sub[si];
        fullColorMap[ci + si] = palette[unsigned];
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
