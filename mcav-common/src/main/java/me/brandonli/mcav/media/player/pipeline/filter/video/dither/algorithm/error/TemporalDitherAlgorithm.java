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
 * rows above it to reduce seams; parallel results can still differ from serial diffusion near boundaries.
 *
 * <p><strong>Instances hold the state of one video stream and must not be shared.</strong> Every instance remembers
 * the palette indices of the previous frame it dithered, so two players dithering through one instance would compare
 * every frame with a frame of the other player, which destroys the stability and makes both pictures flicker. Create
 * one instance per player, for example with {@link
 * me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm#temporalFloydSteinberg()}
 * whenever a player is created, and call {@link #resetTemporalState()} when the player changes its source or seeks.
 * Dithering one frame in parallel through {@link #ditherIntoBytes(ImageBuffer, ForkJoinPool)} is safe.
 *
 * <p>This class is not thread-safe, because it keeps the previous frame; use one instance per stream.
 *
 * @since 1.0.0
 */
public abstract class TemporalDitherAlgorithm extends ErrorDiffusionDither implements ParallelDitherAlgorithm {

  /**
   * The default temporal threshold per channel.
   *
   * @since 1.0.0
   */
  public static final int DEFAULT_TEMPORAL_THRESHOLD = 8;

  /**
   * The default total error at or below which nothing is diffused.
   *
   * @since 1.0.0
   */
  public static final int DEFAULT_ERROR_THRESHOLD = 4;

  /**
   * The default fraction of the error that is diffused.
   *
   * @since 1.0.0
   */
  public static final float DEFAULT_ERROR_STRENGTH = 1.0f;

  private static final int WARMUP_ROWS = 4;
  private static final int STRENGTH_SCALE = 256;

  private static final int MAXIMUM_TEMPORAL_THRESHOLD = 255;
  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private final int temporalThreshold;
  private final int errorThreshold;
  private final int errorStrength;

  private volatile byte @Nullable [] previousIndices;

  /**
   * Creates a new temporal algorithm with default settings.
   *
   * @param palette the palette to reduce images to
   * @param kernel  the kernel that describes how errors are spread
   * @throws NullPointerException if {@code palette}, {@code kernel} is null
   * @since 1.0.0
   */
  protected TemporalDitherAlgorithm(final DitherPalette palette, final DiffusionKernel kernel) {
    this(palette, kernel, DEFAULT_TEMPORAL_THRESHOLD, DEFAULT_ERROR_THRESHOLD, DEFAULT_ERROR_STRENGTH);
  }

  /**
   * Creates a new temporal algorithm.
   *
   * @param palette           the palette to reduce images to
   * @param kernel            the kernel that describes how errors are spread
   * @param temporalThreshold how far a pixel may drift per channel, from 0 to 255, before its color is
   *                          recomputed
   * @param errorThreshold    the total error at or below which no error is diffused
   * @param errorStrength     the fraction of the error that is diffused, from 0 to 1
   * @throws NullPointerException     if {@code palette}, {@code kernel} is null
   * @throws IllegalArgumentException if the temporal threshold is outside 0 through 255, the error
   *                                  threshold is negative, or the strength is outside 0 through 1 or NaN
   * @since 1.0.0
   */
  protected TemporalDitherAlgorithm(
    final DitherPalette palette,
    final DiffusionKernel kernel,
    final int temporalThreshold,
    final int errorThreshold,
    final float errorStrength
  ) {
    super(palette, kernel);
    Preconditions.checkArgument(
      temporalThreshold >= 0 && temporalThreshold <= MAXIMUM_TEMPORAL_THRESHOLD,
      "Temporal threshold must be between 0 and 255 inclusive!"
    );
    Preconditions.checkArgument(errorThreshold >= 0, "Error threshold must not be negative!");
    Preconditions.checkArgument(errorStrength >= 0 && errorStrength <= 1, "Error strength must be between 0 and 1 inclusive!");

    this.temporalThreshold = temporalThreshold;
    this.errorThreshold = errorThreshold;
    this.errorStrength = Math.round(errorStrength * STRENGTH_SCALE);
  }

  /**
   * Forgets the previous frame, so the next frame is dithered from scratch. Call this when the video source
   * changes, after a seek, or when dimensions change, especially if the pixel count stays the same.
   * Serialize this with dithering; an in-flight call can otherwise publish history after this reset.
   *
   * @since 1.0.0
   */
  public void resetTemporalState() {
    this.previousIndices = null;
  }

  /**
   * Dithers a still image in place using the configured error threshold and strength. This operation neither
   * reads nor replaces the previous video frame; temporal reuse applies only to the palette-index methods.
   *
   * @param buffer the ARGB pixels, replaced with palette colors
   * @param width  the positive image width, which must divide the buffer length
   * @throws NullPointerException     if {@code buffer} is null
   * @throws IllegalArgumentException if width is not positive or does not divide the buffer length
   * @since 1.0.0
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);

    if (buffer.length == 0) {
      return;
    }

    final int height = buffer.length / width;
    final byte[] indices = new byte[buffer.length];
    this.ditherStrip(buffer, width, 0, height, null, indices);
    final DitherPalette palette = this.getPalette();
    final int[] colors = palette.getPalette();
    for (int pixel = 0; pixel < buffer.length; pixel++) {
      final int index = indices[pixel] & UNSIGNED_BYTE_MASK;
      buffer[pixel] = colors[index];
    }
  }

  /**
   * Dithers a frame into palette indices, keeping the index of every pixel whose wanted color stayed close to the
   * color it received in the previous frame. The frame is not modified, and becomes the previous frame of the next
   * call.
   *
   * @param image the frame to dither
   * @return the palette index of every pixel, laid out row by row
   * @throws NullPointerException  if {@code image} is null
   * @throws IllegalStateException if the image has been released
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final int height = pixels.length / width;
    final byte[] previous = this.getPreviousIndices(pixels.length);
    final byte[] indices = new byte[pixels.length];
    this.ditherStrip(pixels, width, 0, height, previous, indices);
    this.remember(indices, previous);
    return indices;
  }

  /**
   * Dithers a frame like {@link #ditherIntoBytes(ImageBuffer)}, split into at most one horizontal strip per thread of
   * the pool. Every strip warms up on the rows above it, so the result is nearly identical to the serial one.
   *
   * @param image the frame to dither, which must not be modified while the method runs
   * @param pool  the pool that runs the work
   * @return the palette index of every pixel, laid out row by row
   * @throws NullPointerException                            if the image or pool is null
   * @throws java.util.concurrent.RejectedExecutionException if the pool rejects submitted work
   * @throws IllegalStateException                           if the image has been released
   * @since 1.0.0
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image, final ForkJoinPool pool) {
    checkImage(image);
    Preconditions.checkNotNull(pool, "Pool must not be null!");

    final int width = image.getWidth();
    final int[] pixels = image.getPixels();
    final byte[] previous = this.getPreviousIndices(pixels.length);
    final byte[] indices = new byte[pixels.length];
    final int poolParallelism = pool.getParallelism();
    final int parallelism = Math.max(1, poolParallelism);
    this.ditherStrips(pixels, width, previous, indices, pool, parallelism);
    this.remember(indices, previous);
    return indices;
  }

  // The caller owns the indices, so a copy is kept, in the previous frame's array when the size matches, as its reads are finished.
  private void remember(final byte[] indices, final byte @Nullable [] previous) {
    if (previous == null) {
      this.previousIndices = indices.clone();
      return;
    }

    System.arraycopy(indices, 0, previous, 0, indices.length);
    this.previousIndices = previous;
  }

  private void ditherStrips(
    final int[] pixels,
    final int width,
    final byte @Nullable [] previous,
    final byte[] indices,
    final ForkJoinPool pool,
    final int parallelism
  ) {
    final int height = pixels.length / width;
    final int stripHeight = Math.max(1, (height + parallelism - 1) / parallelism);
    final int stripCount = (height + stripHeight - 1) / stripHeight;
    final Runnable work = () -> this.ditherAllStrips(pixels, width, previous, indices, stripCount, stripHeight);
    final ForkJoinTask<?> task = pool.submit(work);
    task.join();
  }

  private void ditherAllStrips(
    final int[] pixels,
    final int width,
    final byte @Nullable [] previous,
    final byte[] indices,
    final int stripCount,
    final int stripHeight
  ) {
    final IntStream strips = IntStream.range(0, stripCount);
    final IntStream parallelStrips = strips.parallel();
    parallelStrips.forEach(strip -> this.ditherStripAt(pixels, width, previous, indices, strip, stripHeight));
  }

  private void ditherStripAt(
    final int[] pixels,
    final int width,
    final byte @Nullable [] previous,
    final byte[] indices,
    final int strip,
    final int stripHeight
  ) {
    final int height = pixels.length / width;
    final int startY = strip * stripHeight;
    final int endY = Math.min(startY + stripHeight, height);
    this.ditherStrip(pixels, width, startY, endY, previous, indices);
  }

  private byte @Nullable [] getPreviousIndices(final int expectedLength) {
    final byte[] previous = this.previousIndices;
    if (previous == null || previous.length != expectedLength) {
      return null;
    }

    return previous;
  }

  private void ditherStrip(
    final int[] pixels,
    final int width,
    final int startY,
    final int endY,
    final byte @Nullable [] previous,
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
   * Returns the temporal threshold per channel.
   *
   * @return the temporal threshold
   * @since 1.0.0
   */
  public int getTemporalThreshold() {
    return this.temporalThreshold;
  }

  /**
   * Returns the total error at or below which nothing is diffused.
   *
   * @return the error threshold
   * @since 1.0.0
   */
  public int getErrorThreshold() {
    return this.errorThreshold;
  }

  /**
   * Returns the fraction of the error that is diffused.
   *
   * @return the error strength from 0 to 1, rounded to the nearest multiple of 1/256
   * @since 1.0.0
   */
  public float getErrorStrength() {
    return this.errorStrength / (float) STRENGTH_SCALE;
  }

  private final class StripPass {

    private final int[] pixels;
    private final int width;
    private final byte @Nullable [] previous;
    private final byte[] indices;
    private final int[] paletteColors;
    private final byte[] colorMap;
    private final int[] fullColorMap;
    private final ErrorRows errors;
    private int firstWrittenRow;

    StripPass(final int[] pixels, final int width, final byte @Nullable [] previous, final byte[] indices) {
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
      for (int row = firstRow; row < endY; row++) {
        this.ditherRow(row);
        this.errors.finishRow(row);
      }
    }

    private void ditherRow(final int row) {
      final int step = ErrorRows.getScanStep(row);
      final int start = ErrorRows.getScanStart(row, this.width);
      final int end = ErrorRows.getScanEnd(row, this.width);
      for (int column = start; column != end; column += step) {
        this.ditherPixel(column, row, step);
      }
    }

    private void ditherPixel(final int column, final int row, final int step) {
      final int pixelIndex = row * this.width + column;
      final int argb = this.pixels[pixelIndex];
      final int wanted = this.errors.applyPendingError(argb, column, row);
      final int reusedIndex = this.findReusableIndex(pixelIndex, wanted);
      if (reusedIndex >= 0) {
        final int reusedColor = this.paletteColors[reusedIndex];
        this.store(pixelIndex, row, (byte) reusedIndex);
        this.diffuseError(wanted, reusedColor, column, row, step);
        return;
      }

      final int lookup = ErrorRows.getLookupIndex(wanted);
      final byte chosenIndex = this.colorMap[lookup];
      final int chosenColor = this.fullColorMap[lookup];
      this.store(pixelIndex, row, chosenIndex);
      this.diffuseError(wanted, chosenColor, column, row, step);
    }

    private int findReusableIndex(final int pixelIndex, final int wanted) {
      final byte[] previousFrame = this.previous;
      if (previousFrame == null) {
        return -1;
      }

      final int previousIndex = previousFrame[pixelIndex] & UNSIGNED_BYTE_MASK;
      final int previousColor = this.paletteColors[previousIndex];
      final boolean close = TemporalDitherAlgorithm.this.isWithinThreshold(wanted, previousColor);
      return close ? previousIndex : -1;
    }

    private void store(final int pixelIndex, final int row, final byte paletteIndex) {
      if (row >= this.firstWrittenRow) {
        this.indices[pixelIndex] = paletteIndex;
      }
    }

    private void diffuseError(final int wanted, final int chosen, final int column, final int row, final int step) {
      final int errorRed = ErrorRows.red(wanted) - ErrorRows.red(chosen);
      final int errorGreen = ErrorRows.green(wanted) - ErrorRows.green(chosen);
      final int errorBlue = ErrorRows.blue(wanted) - ErrorRows.blue(chosen);
      final int totalError = Math.abs(errorRed) + Math.abs(errorGreen) + Math.abs(errorBlue);
      if (totalError <= TemporalDitherAlgorithm.this.errorThreshold) {
        return;
      }

      final TemporalDitherAlgorithm algorithm = TemporalDitherAlgorithm.this;
      final int scaledRed = algorithm.scale(errorRed);
      final int scaledGreen = algorithm.scale(errorGreen);
      final int scaledBlue = algorithm.scale(errorBlue);
      this.errors.diffuse(column, row, step, scaledRed, scaledGreen, scaledBlue);
    }
  }
}
