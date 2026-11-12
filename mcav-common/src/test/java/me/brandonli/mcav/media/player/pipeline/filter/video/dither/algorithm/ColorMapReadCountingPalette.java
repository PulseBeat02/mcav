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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm;

import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;

// Counts color map reads, which the nearest, ordered and temporal dithers make once per range, band or strip.
final class ColorMapReadCountingPalette implements DitherPalette {

  private final DitherPalette palette;
  private final AtomicInteger colorMapReads;

  ColorMapReadCountingPalette(final DitherPalette palette) {
    this.palette = palette;
    this.colorMapReads = new AtomicInteger();
  }

  @Override
  public int[] getPalette() {
    return this.palette.getPalette();
  }

  @Override
  public int getSize() {
    return this.palette.getSize();
  }

  @Override
  public int getReservedIndices() {
    return this.palette.getReservedIndices();
  }

  @Override
  public byte[] getColorMap() {
    this.colorMapReads.incrementAndGet();

    return this.palette.getColorMap();
  }

  @Override
  public int[] getFullColorMap() {
    return this.palette.getFullColorMap();
  }

  int getColorMapReads() {
    return this.colorMapReads.get();
  }
}
