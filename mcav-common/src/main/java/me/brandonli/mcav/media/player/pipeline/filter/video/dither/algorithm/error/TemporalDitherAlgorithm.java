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

import com.google.common.base.Preconditions;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of error diffusion algorithms that stay stable between video frames.
 *
 * <p>Ordinary error diffusion recomputes every pixel from scratch, so tiny changes in the source, such as video
 * compression noise, shift the dithering pattern of large areas and make them flicker. This class remembers the
 * palette index every pixel received in the previous frame. When the wanted color of a pixel is within the
 * temporal threshold of that previous color, the previous index is kept and only the small remaining difference
 * is diffused. Unchanged areas therefore produce identical output frame after frame, which stops flicker and lets
 * delta encoders skip those areas entirely.
 *
 * <p>Frames can be dithered in parallel by splitting them into horizontal strips. Every strip warms up on a few
 * rows above it, so the seams between strips are invisible.
 *
 * <p><strong>Instances hold the state of one video stream and must not be shared.</strong> Every instance remembers
 * the palette indices of the previous frame it dithered, so two players dithering through one instance would compare
 * every frame with a frame of the other player, which destroys the stability and makes both pictures flicker. Create
 * one instance per player, for example with {@link
 * me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm#temporalFloydSteinberg()}
 * whenever a player is created, and call {@link #resetTemporalState()} when the player changes its source or seeks.
 * Dithering one frame in parallel through {@link #ditherIntoBytes(ImageBuffer, ForkJoinPool)} is safe.
 */
public abstract class TemporalDitherAlgorithm extends ErrorDiffusionDither implements ParallelDitherAlgorithm {

  /**
   * The default temporal threshold per channel.
   */
  public static final int DEFAULT_TEMPORAL_THRESHOLD = 8;

  /**
   * The default total error at or below which nothing is diffused.
   */
  public static final int DEFAULT_ERROR_THRESHOLD = 4;

  /**
   * The default fraction of the error that is diffused.
   */
  public static final float DEFAULT_ERROR_STRENGTH = 1.0f;

  private static final int WARMUP_ROWS = 4;
  private static final int STRENGTH_SCALE = 256;

  private final int temporalThreshold;
  private final int errorThreshold;
  private final int errorStrength;

  private volatile byte@Nullable[] previousIndices;

  /**
   * Constructs a new temporal algorithm with default settings.
   *
   * @param palette the palette to reduce images to
   * @param kernel  the kernel that describes how errors are spread
   */
  protected TemporalDitherAlgorithm(final DitherPalette palette, final DiffusionKernel kernel) {
    this(palette, kernel, DEFAULT_TEMPORAL_THRESHOLD, DEFAULT_ERROR_THRESHOLD, DEFAULT_ERROR_STRENGTH);
  }

  /**
   * Constructs a new temporal algorithm.
   *
   * @param palette           the palette to reduce images to
   * @param kernel            the kernel that describes how errors are spread
   * @param temporalThreshold how far a pixel may drift per channel, from 0 to 255, before its color is
   *                          recomputed
   * @param errorThreshold    the total error at or below which no error is diffused
   * @param errorStrength     the fraction of the error that is diffused, from 0 to 1
   */
  protected TemporalDitherAlgorithm(
    final DitherPalette palette,
    final DiffusionKernel kernel,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    super(palette, kernel);
    Preconditions.checkArgument(temporalThreshold >= 0 && temporalThreshold <= 255, "Temporal threshold must be between 0 and 255");
    Preconditions.checkArgument(errorThreshold >= 0, "Error threshold must not be negative");
    Preconditions.checkArgument(errorStrength >= 0 && errorStrength <= 1, "Error strength must be between 0 and 1");
    this.temporalThreshold = temporalThreshold;
    this.errorThreshold = errorThreshold;
    this.errorStrength = Math.round(errorStrength * STRENGTH_SCALE);
  }

  /**
   * Gets the temporal threshold per channel.
   *
   * @return the temporal threshold
   */
  public int getTemporalThreshold() {
    return this.temporalThreshold;
  }

  /**
   * Gets the total error at or below which nothing is diffused.
   *
   * @return the error threshold
   */
  public int getErrorThreshold() {
    return this.errorThreshold;
  }

  /**
   * Gets the fraction of the error that is diffused.
   *
   * @return the error strength from 0 to 1
   */
  public float getErrorStrength() {
    return this.errorStrength / (float) STRENGTH_SCALE;
  }

  /**
   * Forgets the previous frame, so the next frame is dithered from scratch. Call this when the video source
   * changes or after a seek.
   */
  public void resetTemporalState() {
    this.previousIndices = null;
  }

  /**
   * Dithers a frame into palette indices, keeping the index of every pixel whose wanted color stayed close to the
   * color it received in the previous frame. The frame is not modified, and becomes the previous frame of the next
   * call.
   *
   * @param image the frame to dither
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int height = pixels.length / width;
    final byte[] previous = this.getPreviousIndices(pixels.length);
    final byte[] indices = new byte[pixels.length];
    this.processStrip(pixels, width, 0, height, previous, indices);
    this.remember(indices, previous);
    return indices;
  }

  /**
   * Dithers a frame like {@link #ditherIntoBytes(ImageBuffer)}, split into one horizontal strip per thread of the
   * pool. Every strip warms up on the rows above it, so the result is nearly identical to the serial one.
   *
   * @param image the frame to dither, which must not be modified while the method runs
   * @param pool  the pool that runs the work
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    checkImage(image);
    Preconditions.checkNotNull(pool, "Pool must not be null");

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final byte[] previous = this.getPreviousIndices(pixels.length);
    final byte[] indices = new byte[pixels.length];
    final int poolParallelism = pool.getParallelism();
    final int parallelism = Math.max(1, poolParallelism);
    this.processStrips(pixels, width, previous, indices, pool, parallelism);
    this.remember(indices, previous);
    return indices;
  }

  /**
   * Remembers the indices of a frame for the next one. The caller owns the returned indices and may modify them, so
   * the algorithm keeps a copy. The indices of the previous frame were only read while the frame was dithered, which
   * has finished, so their array receives the copy instead of a new one whenever the frame has the same size.
   *
   * @param indices  the indices of the frame that was just dithered
   * @param previous the indices of the frame before, or {@code null} if there were none of the same size
   */
  private void remember(final byte[] indices, final byte@Nullable[] previous) {
    if (previous == null) {
      this.previousIndices = indices.clone();
      return;
    }
    System.arraycopy(indices, 0, previous, 0, indices.length);
    this.previousIndices = previous;
  }

  private void processStrips(
    final int[] pixels,
    final int width,
    final byte@Nullable[] previous,
    final byte[] indices,
    final ForkJoinPool pool,
    final int parallelism
  ) {
    final int height = pixels.length / width;
    final int stripHeight = Math.max(1, (height + parallelism - 1) / parallelism);
    final int stripCount = (height + stripHeight - 1) / stripHeight;
    final ForkJoinTask<?> task = pool.submit(() -> {
      final IntStream strips = IntStream.range(0, stripCount);
      final IntStream parallelStrips = strips.parallel();
      parallelStrips.forEach(strip -> {
        final int startY = strip * stripHeight;
        final int endY = Math.min(startY + stripHeight, height);
        this.processStrip(pixels, width, startY, endY, previous, indices);
      });
    });
    task.join();
  }

  private byte@Nullable[] getPreviousIndices(final int expectedLength) {
    final byte[] previous = this.previousIndices;
    if (previous == null || previous.length != expectedLength) {
      return null;
    }
    return previous;
  }

  /**
   * Dithers the rows from {@code startY} to {@code endY}, warming up on the rows above so the strip joins its
   * neighbors seamlessly.
   */
  private void processStrip(
    final int[] pixels,
    final int width,
    final int startY,
    final int endY,
    final byte@Nullable[] previous,
    final byte[] indices
  ) {
    final StripPass pass = new StripPass(pixels, width, previous, indices);
    pass.run(startY, endY);
  }

  private boolean isWithinThreshold(final int wanted, final int color) {
    final int differenceRed = ErrorRows.red(wanted) - ErrorRows.red(color);
    final int differenceGreen = ErrorRows.green(wanted) - ErrorRows.green(color);
    final int differenceBlue = ErrorRows.blue(wanted) - ErrorRows.blue(color);
    final int deltaRed = Math.abs(differenceRed);
    final int deltaGreen = Math.abs(differenceGreen);
    final int deltaBlue = Math.abs(differenceBlue);
    return deltaRed <= this.temporalThreshold && deltaGreen <= this.temporalThreshold && deltaBlue <= this.temporalThreshold;
  }

  private int scale(final int error) {
    return (error * this.errorStrength) / STRENGTH_SCALE;
  }

  /**
   * One pass of the algorithm over one horizontal strip of an image.
   */
  private final class StripPass {

    private final int[] pixels;
    private final int width;
    private final byte@Nullable[] previous;
    private final byte[] indices;
    private final int[] paletteColors;
    private final byte[] colorMap;
    private final int[] fullColorMap;
    private final ErrorRows errors;
    private int firstWrittenRow;

    StripPass(final int[] pixels, final int width, final byte@Nullable[] previous, final byte[] indices) {
      final DitherPalette palette = TemporalDitherAlgorithm.this.getPalette();
      final DiffusionKernel kernel = TemporalDitherAlgorithm.this.getKernel();
      this.pixels = pixels;
      this.width = width;
      this.previous = previous;
      this.indices = indices;
      this.paletteColors = palette.getPalette();
      this.colorMap = palette.getColorMap();
      this.fullColorMap = palette.getFullColorMap();
      this.errors = new ErrorRows(kernel, width);
    }

    void run(final int startY, final int endY) {
      this.firstWrittenRow = startY;
      final int firstRow = Math.max(0, startY - WARMUP_ROWS);
      for (int y = firstRow; y < endY; y++) {
        this.ditherRow(y);
        this.errors.finishRow(y);
      }
    }

    private void ditherRow(final int y) {
      final int step = ErrorRows.getScanStep(y);
      final int start = ErrorRows.getScanStart(y, this.width);
      final int end = ErrorRows.getScanEnd(y, this.width);
      for (int x = start; x != end; x += step) {
        this.ditherPixel(x, y, step);
      }
    }

    private void ditherPixel(final int x, final int y, final int step) {
      final int pixelIndex = y * this.width + x;
      final int argb = this.pixels[pixelIndex];
      final int wanted = this.errors.applyPendingError(argb, x, y);
      final int reusedIndex = this.findReusableIndex(pixelIndex, wanted);
      if (reusedIndex >= 0) {
        final int reusedColor = this.paletteColors[reusedIndex];
        this.store(pixelIndex, y, (byte) reusedIndex);
        this.diffuseError(wanted, reusedColor, x, y, step, false);
        return;
      }
      final int lookup = ErrorRows.getLookupIndex(wanted);
      final byte chosenIndex = this.colorMap[lookup];
      final int chosenColor = this.fullColorMap[lookup];
      this.store(pixelIndex, y, chosenIndex);
      this.diffuseError(wanted, chosenColor, x, y, step, true);
    }

    /**
     * Finds the palette index the pixel had in the previous frame, if its wanted color is still close to it.
     *
     * @return the previous palette index from 0 to 255, or -1 if the pixel needs a new color
     */
    private int findReusableIndex(final int pixelIndex, final int wanted) {
      final byte[] previousFrame = this.previous;
      if (previousFrame == null) {
        return -1;
      }
      final int previousIndex = previousFrame[pixelIndex] & 0xFF;
      final int previousColor = this.paletteColors[previousIndex];
      final boolean close = TemporalDitherAlgorithm.this.isWithinThreshold(wanted, previousColor);
      return close ? previousIndex : -1;
    }

    private void store(final int pixelIndex, final int y, final byte paletteIndex) {
      // the warm-up rows above the strip belong to the neighboring strip, so only their errors are kept
      if (y >= this.firstWrittenRow) {
        this.indices[pixelIndex] = paletteIndex;
      }
    }

    private void diffuseError(final int wanted, final int chosen, final int x, final int y, final int step, final boolean scaled) {
      final int errorRed = ErrorRows.red(wanted) - ErrorRows.red(chosen);
      final int errorGreen = ErrorRows.green(wanted) - ErrorRows.green(chosen);
      final int errorBlue = ErrorRows.blue(wanted) - ErrorRows.blue(chosen);
      final int totalError = Math.abs(errorRed) + Math.abs(errorGreen) + Math.abs(errorBlue);
      if (totalError <= TemporalDitherAlgorithm.this.errorThreshold) {
        return;
      }
      if (!scaled) {
        this.errors.diffuse(x, y, step, errorRed, errorGreen, errorBlue);
        return;
      }
      final TemporalDitherAlgorithm algorithm = TemporalDitherAlgorithm.this;
      final int scaledRed = algorithm.scale(errorRed);
      final int scaledGreen = algorithm.scale(errorGreen);
      final int scaledBlue = algorithm.scale(errorBlue);
      this.errors.diffuse(x, y, step, scaledRed, scaledGreen, scaledBlue);
    }
  }
}
