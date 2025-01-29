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
package me.brandonli.mcav.media.video.dither.load;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveTask;

/**
 * @author jetp250
 * <p>
 * LoadRed is a subclass of RecursiveTask responsible for parallel processing
 * of color matching operations based on the red channel of an RGB color palette.
 * It forms the root of a hierarchical task structure that delegates tasks
 * for processing green channel computations to the LoadGreen class.
 * <p>
 * This class operates within the Fork/Join framework to improve computational
 * efficiency by dividing the problem into smaller subtasks, executing them
 * in parallel, and combining the results.
 * <p>
 * Key Responsibilities:
 * - Initialize and distribute color matching tasks for the green channel
 * by creating LoadGreen instances.
 * - Aggregate results from the green channel computations into a single
 * byte array representing the closest color matches for all combinations
 * of green and blue channels with the specified red channel value.
 * <p>
 * Constructor:
 * - Requires an integer array representing the color palette and an integer
 * specifying the red channel value.
 * <p>
 * Methods:
 * - compute(): Entry point for the task execution, coordinating green channel
 * computations and collecting their results.
 * - forkGreen(List<LoadGreen>): Creates and forks LoadGreen instances to
 * handle parallel processing for the green channel.
 * - copyColors(List<LoadGreen>): Combines results from all green channel
 * LoadGreen subtasks into a single byte array.
 */
public final class LoadRed extends RecursiveTask<byte[]> {

  private static final long serialVersionUID = 7576804473009302589L;
  private final int r;
  private final int[] palette;

  public LoadRed(final int[] palette, final int r) {
    this.r = r;
    this.palette = palette;
  }

  @Override
  protected byte[] compute() {
    final List<LoadGreen> greenSub = new ArrayList<>(128);
    this.forkGreen(greenSub);
    return this.copyColors(greenSub);
  }

  private void forkGreen(final List<LoadGreen> greenSub) {
    for (int g = 0; g < 256; g += 2) {
      final LoadGreen green = new LoadGreen(this.palette, this.r, g);
      greenSub.add(green);
      green.fork();
    }
  }

  private byte[] copyColors(final List<LoadGreen> greenSub) {
    final byte[] values = new byte[16384];
    for (int i = 0; i < 128; i++) {
      final byte[] sub = greenSub.get(i).join();
      final int index = i << 7;
      System.arraycopy(sub, 0, values, index, 128);
    }
    return values;
  }
}
