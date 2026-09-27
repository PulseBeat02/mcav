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
package me.brandonli.mcav.media.mcv2.encode;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.MAX_GRID;
import static me.brandonli.mcav.media.mcv2.Mcv2Format.ROOT_SIZE;

import me.brandonli.mcav.media.mcv2.Reconstruction;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The encoder's kernels in Java: the oracle the native ones are measured against, and what every search runs where no
 * native library loads, and the reference search always.
 */
final class JavaKernels extends Kernels {

  /** The kernels of every coder that has no native library. */
  static final Kernels.Factory FACTORY = JavaKernels::new;

  private final Reconstruction.Score score = new Reconstruction.Score();

  private final Reconstruction.Scratch scratch = new Reconstruction.Scratch();

  private final double[] fitScratch = new double[ROOT_SIZE * MAX_GRID];

  private final int[] cellScratch = new int[FastFits.CELL_SUMS];

  private final long[] clusterScratch = new long[FastFits.CLUSTER_SUMS];

  @Override
  void start(final int[] source, final double rate, final double limit) {
    this.score.start(source, rate, limit);
  }

  @Override
  long distortion() {
    return this.score.distortion();
  }

  @Override
  boolean predicted(final int[] prediction, final int size, final int[] out) {
    return Reconstruction.predicted(prediction, size, out, this.score);
  }

  @Override
  boolean solid(final int color, final int size, final int[] out) {
    return Reconstruction.solid(color, size, out, this.score);
  }

  @Override
  boolean palette(final byte[] record, final int offset, final int size, final int[] out) {
    return Reconstruction.palette(record, offset, size, out, this.score);
  }

  @Override
  boolean intraGrid(final byte[] record, final int offset, final int grid, final int size, final int[] out) {
    return Reconstruction.intraGrid(record, offset, grid, size, this.scratch, out, this.score);
  }

  @Override
  boolean residualGrid(
    final int[] prediction,
    final byte[] record,
    final int offset,
    final int grid,
    final int q,
    final int size,
    final int[] out
  ) {
    return Reconstruction.residualGrid(prediction, record, offset, grid, q, size, this.scratch, out, this.score);
  }

  @Override
  boolean reduced(
    final int@Nullable[] prediction,
    final byte[] record,
    final int offset,
    final int luma,
    final int chroma,
    final int q,
    final int size,
    final int[] out
  ) {
    return Reconstruction.reduced(prediction, record, offset, luma, chroma, q, size, this.scratch, out, this.score);
  }

  @Override
  boolean compact(
    final int[] prediction,
    final byte[] record,
    final int body,
    final int kind,
    final int q,
    final int size,
    final int[] out
  ) {
    return Reconstruction.compact(prediction, record, body, kind, q, size, this.scratch, out, this.score);
  }

  @Override
  void predict(
    final byte[] reference,
    final int width,
    final int height,
    final int x,
    final int y,
    final int size,
    final int mx,
    final int my,
    final int[] out
  ) {
    Reconstruction.predict(reference, width, height, x, y, size, mx, my, out);
  }

  @Override
  void fit(
    final float[] values,
    final int offset,
    final int stride,
    final int size,
    final int grid,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    Fits.fit(values, offset, stride, size, grid, this.fitScratch, out, outOffset, outStride);
  }

  @Override
  void cellSums(final int[] source, final int size, final int[] sums) {
    FastFits.cellSums(source, size, sums);
  }

  @Override
  void lumaResidual(final int[] source, final int[] prediction, final int size, final float[] nodes) {
    FastFits.lumaResidual(source, prediction, size, this.cellScratch, nodes);
  }

  @Override
  void cluster(final int[] source, final int size, final float[] endpoints) {
    FastFits.cluster(source, size, this.clusterScratch, endpoints);
  }

  @Override
  void paletteCluster(final int[] source, final int count, final float[] endpoints) {
    PaletteFit.cluster(source, count, endpoints);
  }

  @Override
  void finish(
    final int[] source,
    final int count,
    final float[] endpoints,
    final boolean quantize,
    final int[] colors,
    final byte[] selectors
  ) {
    PaletteFit.finish(source, count, endpoints, quantize, colors, selectors);
  }

  @Override
  boolean finishPattern(
    final int[] source,
    final int size,
    final float[] endpoints,
    final boolean quantize,
    final int[] colors,
    final byte[] selectors
  ) {
    return PaletteFit.finishPattern(source, size, endpoints, quantize, colors, selectors);
  }

  @Override
  int seeded(
    final byte[] reference,
    final int width,
    final int height,
    final int[] source,
    final int x,
    final int y,
    final int size,
    final int globalX,
    final int globalY,
    final int range,
    final boolean halfPixel,
    final int[] seeds
  ) {
    return MotionSearch.seeded(reference, width, height, source, x, y, size, globalX, globalY, range, halfPixel, seeds);
  }

  @Override
  void loadSource(final byte[] image, final int width, final int height, final int x, final int y, final int size, final int[] source) {
    for (int py = 0; py < size; py++) {
      final int sy = Math.min(y + py, height - 1);
      for (int px = 0; px < size; px++) {
        final int sx = Math.min(x + px, width - 1);
        final int from = (sy * width + sx) * CHANNELS;
        final int to = (py * size + px) * CHANNELS;
        source[to] = image[from] & 0xFF;
        source[to + 1] = image[from + 1] & 0xFF;
        source[to + 2] = image[from + 2] & 0xFF;
      }
    }
  }

  @Override
  void halve(final int[] block, final int size, final int[] out) {
    BlockCoder.halve(block, size, out);
  }

  @Override
  void ycocg(final int[] source, final int count, final boolean chroma, final float[] out) {
    for (int i = 0; i < count * CHANNELS; i += CHANNELS) {
      final int r = source[i];
      final int g = source[i + 1];
      final int b = source[i + 2];
      out[i] = (r + 2 * g + b) * 0.25f;
      if (chroma) {
        out[i + 1] = (r - b) * 0.5f;
        out[i + 2] = (-r + 2 * g - b) * 0.25f;
      }
    }
  }

  @Override
  void residualTarget(final float[] ycocg, final int[] prediction, final int count, final boolean chroma, final float[] target) {
    for (int i = 0; i < count * CHANNELS; i += CHANNELS) {
      final float r = prediction[i] * 0.25f;
      final float g = prediction[i + 1] * 0.25f;
      final float b = prediction[i + 2] * 0.25f;
      target[i] = ycocg[i] - (r + 2 * g + b) * 0.25f;
      if (chroma) {
        target[i + 1] = ycocg[i + 1] - (r - b) * 0.5f;
        target[i + 2] = ycocg[i + 2] - (-r + 2 * g - b) * 0.25f;
      }
    }
  }

  @Override
  void cellMeans(
    final float[] target,
    final int size,
    final int channel,
    final int grid,
    final float[] out,
    final int outOffset,
    final int outStride
  ) {
    final int side = size / grid;
    for (int j = 0; j < grid; j++) {
      for (int i = 0; i < grid; i++) {
        double sum = 0;
        for (int y = j * side; y < (j + 1) * side; y++) {
          for (int x = i * side; x < (i + 1) * side; x++) {
            sum += target[(y * size + x) * CHANNELS + channel];
          }
        }
        out[outOffset + (j * grid + i) * outStride] = (float) (sum / (side * side));
      }
    }
  }
}
