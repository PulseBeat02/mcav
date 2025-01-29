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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.load;

import java.io.Serial;
import java.util.concurrent.RecursiveTask;

/**
 * @author jetp250
 * <p>
 * LoadBlue is utilized as a RecursiveTask to compute the closest match for
 * the blue channel of a given RGB color against a defined color palette.
 * It is part of a hierarchical task structure for color matching, which is
 * invoked by its parent task class, LoadGreen.
 * <p>
 * This class performs a calculation to determine the palette index with
 * minimum color distance for a specific blue value, taking into consideration
 * the red and green values as context.
 * <p>
 * The distance calculation between colors is based on a weighted Euclidean
 * model that adjusts weights dynamically based on the color's red component
 * to account for perceptual differences in human color perception.
 * <p>
 * Instances of this class are designed to be used in parallel processing,
 * leveraging the Fork/Join framework to improve performance on large datasets.
 */
public final class LoadBlue extends RecursiveTask<Byte> {

  @Serial
  private static final long serialVersionUID = -4077843876709214664L;

  private final int r, g, b;
  private final int[] palette;

  LoadBlue(final int[] palette, final int r, final int g, final int b) {
    this.r = r;
    this.g = g;
    this.b = b;
    this.palette = palette;
  }

  @Override
  protected Byte compute() {
    int val = 0;
    float best_distance = Float.MAX_VALUE;
    float distance;
    int col;
    final float f = 1 / 256F;
    for (int i = 4; i < this.palette.length; ++i) {
      col = this.palette[i];
      distance = this.calculateDistance(col, f);
      if (distance < best_distance) {
        best_distance = distance;
        val = i;
      }
    }
    return (byte) val;
  }

  private float calculateDistance(final int col, final float f) {
    final int r2 = (col >> 16) & 0xFF;
    final int g2 = (col >> 8) & 0xFF;
    final int b2 = col & 0xFF;
    final float red_avg = (this.r + r2) * 0.5f;
    final int redVal = this.r - r2;
    final int greenVal = this.g - g2;
    final int blueVal = this.b - b2;
    final float weight_red = 2.0f + red_avg * f;
    final float weight_green = 4.0f;
    final float weight_blue = 2.0f + (255.0f - red_avg) * f;
    return weight_red * redVal * redVal + weight_green * greenVal * greenVal + weight_blue * blueVal * blueVal;
  }
}
