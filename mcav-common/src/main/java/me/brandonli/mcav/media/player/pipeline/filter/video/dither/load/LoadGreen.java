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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.load;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveTask;

/**
 * @author jetp250
 * <p>
 * LoadGreen is a subclass of RecursiveTask that facilitates the parallel
 * computation of closest color matches for a specific green channel value
 * in relation to a predefined color palette. It operates within a hierarchical
 * task structure, receiving the red and green components as context for its
 * calculations.
 * <p>
 * This class focuses on dividing the blue channel computation into smaller
 * tasks by forking instances of the LoadBlue class, which are processed
 * concurrently to improve computational efficiency. Once all the blue channel
 * tasks are completed, it aggregates their results.
 * <p>
 * Key responsibilities:
 * - Splits the computation of the blue channel into multiple parallel tasks
 * by creating and forking instances of LoadBlue.
 * - Combines results from all LoadBlue instances to produce an array of palette
 * index matches.
 * <p>
 * Instances of this class are designed to be part of a larger color-matching
 * process that includes red and blue channel tasks, leveraging the Fork/Join
 * framework to achieve better performance when indexing colors in large
 * color palettes.
 */
public final class LoadGreen extends RecursiveTask<byte[]> {

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
