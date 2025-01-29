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

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveTask;

/**
 * Thanks
 * @author jetp250
 */
final class LoadGreen extends RecursiveTask<byte[]> {

  @Serial
  private static final long serialVersionUID = -3744276292242087941L;

  private final int r;
  private final int g;
  private final int[] palette;

  LoadGreen(final int[] palette, final int r, final int g) {
    this.r = r;
    this.g = g;
    this.palette = palette;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected byte[] compute() {
    final List<LoadBlue> blueSub = new ArrayList<>(128);
    this.forkBlue(blueSub);
    return this.getMatches(blueSub);
  }

  private void forkBlue(final List<LoadBlue> blueSub) {
    for (int b = 0; b < 256; b += 2) {
      final LoadBlue blue = new LoadBlue(this.palette, this.r, this.g, b);
      blueSub.add(blue);
      blue.fork();
    }
  }

  private byte[] getMatches(final List<LoadBlue> blueSub) {
    final byte[] matches = new byte[128];
    for (int i = 0; i < 128; i++) {
      matches[i] = blueSub.get(i).join();
    }
    return matches;
  }
}
