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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Abstract base for error-diffusion dithers with temporal coherence and strip-level parallelism.
 *
 * <p>Each frame, pixels whose error-adjusted colour is within {@link #DEFAULT_TEMPORAL_THRESHOLD}
 * per channel of the palette colour output on the previous frame reuse that index unchanged,
 * producing bitwise-identical bytes in static regions, which allows quadrant-hash dirty detection
 * to skip unchanged tiles at no extra cost. Residual error at skip sites is still propagated
 * to avoid banding at stable/moving boundaries. Subclasses supply the kernel via
 * {@link #diffuseError}.
 */
public abstract class TemporalDitherAlgorithm extends ErrorDiffusionDither implements ParallelDitherAlgorithm {

  /** Default per-channel tolerance for reusing a previous palette index. */
  public static final int DEFAULT_TEMPORAL_THRESHOLD = 8;

  /** Default minimum total error ({@code |ΔR|+|ΔG|+|ΔB|}) below which diffusion is skipped. */
  public static final int DEFAULT_ERROR_THRESHOLD = 4;

  /** Default error diffusion strength (1.0 = full). */
  public static final float DEFAULT_ERROR_STRENGTH = 1.0f;

  private static final int WARMUP_ROWS = 4;

  private final int temporalThreshold;
  private final int errorThreshold;
  private final int errorStrength256;

  private volatile byte@Nullable[] previousDithered;

  /**
   * Constructs a temporal dither algorithm with the specified palette and default settings.
   *
   * @param palette the colour palette to use for quantisation
   */
  protected TemporalDitherAlgorithm(final DitherPalette palette) {
    this(palette, DEFAULT_TEMPORAL_THRESHOLD, DEFAULT_ERROR_THRESHOLD, DEFAULT_ERROR_STRENGTH);
  }

  /**
   * Constructs a temporal dither algorithm with fully configurable settings.
   *
   * @param palette           the colour palette to use for quantisation
   * @param temporalThreshold per-channel tolerance for reusing a previous palette index (≥ 0)
   * @param errorThreshold    minimum total error below which diffusion is skipped (≥ 0)
   * @param errorStrength     error diffusion strength in [0.0, 1.0]
   */
  protected TemporalDitherAlgorithm(
    final DitherPalette palette,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    super(palette);
    this.temporalThreshold = Math.max(0, temporalThreshold);
    this.errorThreshold = Math.max(0, errorThreshold);
    this.errorStrength256 = Math.round(Math.max(0f, Math.min(1f, errorStrength)) * 256f);
  }

  /**
   * Distributes the quantisation error for the pixel at column {@code x} to its neighbours.
   *
   * <p>Row buffers are flat {@code int[width * 3]} arrays where {@code buf[x*3 + c]} holds the
   * accumulated error for column {@code x}, channel {@code c} (0=R, 1=G, 2=B).
   *
   * @param x        current pixel column
   * @param width    row width in pixels
   * @param current  error accumulator for the current row
   * @param next     error accumulator for the next row
   * @param hasNextY whether a next row exists
   * @param forward  {@code true} for left-to-right scan, {@code false} for right-to-left
   * @param dR       red quantisation error
   * @param dG       green quantisation error
   * @param dB       blue quantisation error
   */
  protected abstract void diffuseError(
    int x,
    int width,
    int[] current,
    int[] next,
    boolean hasNextY,
    boolean forward,
    int dR,
    int dG,
    int dB
  );

  /** {@inheritDoc} */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    final DitherPalette palette = this.getPalette();
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    final byte[] result = new byte[pixels.length];
    final byte[] prev = this.snapshotPrevious(pixels.length);
    this.processStrip(pixels, width, pixels.length / width, 0, pixels.length / width, prev, result, palette);
    this.previousDithered = result.clone();
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    final DitherPalette palette = this.getPalette();
    final int[] pixels = image.getPixels();
    final int width = image.getWidth();
    final int height = pixels.length / width;
    final byte[] result = new byte[pixels.length];
    final byte[] prev = this.snapshotPrevious(pixels.length);
    final int parallelism = Math.max(1, pool.getParallelism());
    final int stripHeight = Math.max(1, (height + parallelism - 1) / parallelism);
    pool
      .submit(() ->
        IntStream.range(0, parallelism)
          .parallel()
          .forEach(strip -> {
            final int startY = strip * stripHeight;
            if (startY >= height) {
              return;
            }
            this.processStrip(pixels, width, height, startY, Math.min(startY + stripHeight, height), prev, result, palette);
          })
      )
      .join();
    this.previousDithered = result.clone();
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public void dither(final int[] buffer, final int width) {
    final DitherPalette palette = this.getPalette();
    final int height = buffer.length / width;
    final int[] a = new int[width * 3];
    final int[] b = new int[width * 3];
    int[] cur = a;
    int[] nxt = b;
    for (int y = 0; y < height; y++) {
      final int[] tmp = cur;
      cur = nxt;
      nxt = tmp;
      Arrays.fill(nxt, 0);
      final boolean hasNextY = y < height - 1;
      final int yIndex = y * width;
      final boolean forward = (y & 1) == 0;
      if (forward) {
        for (int x = 0; x < width; x++) {
          this.ditherPixelInPlace(buffer, palette, cur, nxt, yIndex, x, width, hasNextY, true);
        }
      } else {
        for (int x = width - 1; x >= 0; x--) {
          this.ditherPixelInPlace(buffer, palette, cur, nxt, yIndex, x, width, hasNextY, false);
        }
      }
    }
  }

  /**
   * Clears the internal temporal state. Call when the video source or resolution changes so the
   * next frame is processed without stale references from a previous scene.
   */
  public void resetTemporalState() {
    this.previousDithered = null;
  }

  /**
   * Returns the per-channel temporal skip threshold.
   *
   * @return temporal threshold
   */
  public int getTemporalThreshold() {
    return this.temporalThreshold;
  }

  /**
   * Returns the minimum total error below which diffusion is skipped.
   *
   * @return error threshold
   */
  public int getErrorThreshold() {
    return this.errorThreshold;
  }

  /**
   * Returns the error diffusion strength in [0.0, 1.0].
   *
   * @return error strength
   */
  public float getErrorStrength() {
    return this.errorStrength256 / 256f;
  }

  private void processStrip(
    final int[] pixels,
    final int width,
    final int height,
    final int startY,
    final int endY,
    final byte@Nullable[] prev,
    final byte[] result,
    final DitherPalette palette
  ) {
    final int[] paletteRgbs = palette.getPalette();
    final int[] a = new int[width * 3];
    final int[] b = new int[width * 3];
    int[] cur = a;
    int[] nxt = b;
    for (int y = Math.max(0, startY - WARMUP_ROWS); y < endY; y++) {
      final int[] tmp = cur;
      cur = nxt;
      nxt = tmp;
      Arrays.fill(nxt, 0);
      final boolean write = y >= startY;
      final boolean hasNextY = y < height - 1;
      final int yIndex = y * width;
      final boolean forward = (y & 1) == 0;
      if (forward) {
        for (int x = 0; x < width; x++) {
          this.processPixel(x, yIndex, width, pixels, prev, result, paletteRgbs, palette, cur, nxt, hasNextY, true, write);
        }
      } else {
        for (int x = width - 1; x >= 0; x--) {
          this.processPixel(x, yIndex, width, pixels, prev, result, paletteRgbs, palette, cur, nxt, hasNextY, false, write);
        }
      }
    }
  }

  private void processPixel(
    final int x,
    final int yIndex,
    final int width,
    final int[] pixels,
    final byte@Nullable[] prev,
    final byte[] result,
    final int[] paletteRgbs,
    final DitherPalette palette,
    final int[] cur,
    final int[] nxt,
    final boolean hasNextY,
    final boolean forward,
    final boolean write
  ) {
    final int idx = yIndex + x;
    final int bufIdx = x * 3;
    final int rgb = pixels[idx];
    final int r = clamp(((rgb >> 16) & 0xFF) + cur[bufIdx]);
    final int g = clamp(((rgb >> 8) & 0xFF) + cur[bufIdx + 1]);
    final int b = clamp((rgb & 0xFF) + cur[bufIdx + 2]);

    if (prev != null) {
      final byte prevIdx = prev[idx];
      final int prevRgb = paletteRgbs[(prevIdx + 256) & 0xFF];
      final int prevR = (prevRgb >> 16) & 0xFF;
      final int prevG = (prevRgb >> 8) & 0xFF;
      final int prevB = prevRgb & 0xFF;
      if (
        Math.abs(r - prevR) <= this.temporalThreshold &&
        Math.abs(g - prevG) <= this.temporalThreshold &&
        Math.abs(b - prevB) <= this.temporalThreshold
      ) {
        if (write) {
          result[idx] = prevIdx;
        }
        final int residR = r - prevR;
        final int residG = g - prevG;
        final int residB = b - prevB;
        if (Math.abs(residR) + Math.abs(residG) + Math.abs(residB) > this.errorThreshold) {
          this.diffuseError(x, width, cur, nxt, hasNextY, forward, residR, residG, residB);
        }
        return;
      }
    }

    final int closest = DitherUtils.getBestFullColor(palette, r, g, b);
    final int cR = (closest >> 16) & 0xFF;
    final int cG = (closest >> 8) & 0xFF;
    final int cB = closest & 0xFF;
    if (write) {
      result[idx] = DitherUtils.getBestColor(palette, cR, cG, cB);
    }
    final int dR = r - cR;
    final int dG = g - cG;
    final int dB = b - cB;
    if (Math.abs(dR) + Math.abs(dG) + Math.abs(dB) > this.errorThreshold) {
      this.diffuseError(x, width, cur, nxt, hasNextY, forward, this.scale(dR), this.scale(dG), this.scale(dB));
    }
  }

  private void ditherPixelInPlace(
    final int[] buffer,
    final DitherPalette palette,
    final int[] cur,
    final int[] nxt,
    final int yIndex,
    final int x,
    final int width,
    final boolean hasNextY,
    final boolean forward
  ) {
    final int idx = yIndex + x;
    final int bufIdx = x * 3;
    final int rgb = buffer[idx];
    final int r = clamp(((rgb >> 16) & 0xFF) + cur[bufIdx]);
    final int g = clamp(((rgb >> 8) & 0xFF) + cur[bufIdx + 1]);
    final int b = clamp((rgb & 0xFF) + cur[bufIdx + 2]);
    final int closest = DitherUtils.getBestFullColor(palette, r, g, b);
    final int dR = r - ((closest >> 16) & 0xFF);
    final int dG = g - ((closest >> 8) & 0xFF);
    final int dB = b - (closest & 0xFF);
    if (Math.abs(dR) + Math.abs(dG) + Math.abs(dB) > this.errorThreshold) {
      this.diffuseError(x, width, cur, nxt, hasNextY, forward, this.scale(dR), this.scale(dG), this.scale(dB));
    }
    buffer[idx] = closest;
  }

  private byte@Nullable[] snapshotPrevious(final int expectedLength) {
    final byte[] prev = this.previousDithered;
    return (prev != null && prev.length == expectedLength) ? prev : null;
  }

  private int scale(final int delta) {
    return (delta * this.errorStrength256) >> 8;
  }

  static int clamp(final int v) {
    return (v & ~255) == 0 ? v : (v < 0 ? 0 : 255);
  }
}
