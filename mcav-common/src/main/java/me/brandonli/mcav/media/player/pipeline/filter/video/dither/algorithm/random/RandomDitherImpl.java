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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.random;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Objects;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherUtils;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Random dithering, which adds uniform noise to every channel before picking the closest palette color. The
 * noise hides color banding but produces a grainy picture that changes every frame.
 *
 * <p>An algorithm created without a random number provider gives every thread its own fast generator, so it can be
 * shared by any number of players and threads. An algorithm created with a provider hands that one provider to every
 * thread, so it is only thread-safe if the provider is.
 */
public final class RandomDitherImpl extends AbstractDitherAlgorithm implements RandomDither {

  private final int weight;
  private final ThreadLocal<@Nullable RandomNumberProvider> providers;

  /**
   * Constructs a new random dithering algorithm with a fast random number generator per thread, which can be shared
   * between threads.
   *
   * @param palette the palette to reduce images to
   * @param weight  the largest noise value added to or subtracted from a channel, see {@link #NORMAL_WEIGHT}
   */
  public RandomDitherImpl(final DitherPalette palette, final int weight) {
    super(palette);
    checkWeight(weight);
    this.weight = weight;
    this.providers = ThreadLocal.withInitial(XoroshiroRandomProvider::new);
  }

  /**
   * Constructs a new random dithering algorithm that draws its noise from one provider. The algorithm is only
   * thread-safe if the provider is.
   *
   * @param palette the palette to reduce images to
   * @param weight  the largest noise value added to or subtracted from a channel, see {@link #NORMAL_WEIGHT}
   * @param random  the random number generator every thread uses
   */
  public RandomDitherImpl(final DitherPalette palette, final int weight, final RandomNumberProvider random) {
    super(palette);
    checkWeight(weight);
    Preconditions.checkNotNull(random, "Random number provider must not be null");
    this.weight = weight;
    this.providers = ThreadLocal.withInitial(() -> random);
  }

  private static void checkWeight(final int weight) {
    Preconditions.checkArgument(weight >= 0 && weight <= 255, "Weight must be between 0 and 255");
  }

  /**
   * Gets the largest noise value added to or subtracted from a channel.
   *
   * @return the weight
   */
  public int getWeight() {
    return this.weight;
  }

  /**
   * Gets the random number provider the calling thread draws its noise from.
   *
   * @return the provider of the calling thread
   */
  @VisibleForTesting
  RandomNumberProvider getRandomNumberProvider() {
    final RandomNumberProvider provider = this.providers.get();
    return Objects.requireNonNull(provider, "Every thread gets a provider from the initial value");
  }

  /**
   * Dithers an image into palette indices by adding noise to every channel before picking the closest color. The
   * image is not modified.
   *
   * @param image the image to dither
   * @return the palette index of every pixel, laid out row by row
   */
  @Override
  public byte[] ditherIntoBytes(final ImageBuffer image) {
    checkImage(image);

    final DitherPalette palette = this.getPalette();
    final byte[] colorMap = palette.getColorMap();
    final int[] pixels = image.getPixels();
    final byte[] indices = new byte[pixels.length];
    final RandomNumberProvider random = this.getRandomNumberProvider();
    for (int index = 0; index < pixels.length; index++) {
      final int lookup = this.lookupWithNoise(pixels[index], random);
      indices[index] = colorMap[lookup];
    }
    return indices;
  }

  /**
   * Dithers pixels in place by adding noise to every channel and replacing every pixel with the closest palette
   * color.
   *
   * @param buffer the ARGB pixels laid out row by row, which are replaced with palette colors
   * @param width  the width of the image, which must divide the length of the buffer
   */
  @Override
  public void dither(final int[] buffer, final int width) {
    checkBuffer(buffer, width);

    final DitherPalette palette = this.getPalette();
    final int[] fullColorMap = palette.getFullColorMap();
    final RandomNumberProvider random = this.getRandomNumberProvider();
    for (int index = 0; index < buffer.length; index++) {
      final int lookup = this.lookupWithNoise(buffer[index], random);
      buffer[index] = fullColorMap[lookup];
    }
  }

  private int lookupWithNoise(final int argb, final RandomNumberProvider random) {
    final int redNoise = this.nextNoise(random);
    final int greenNoise = this.nextNoise(random);
    final int blueNoise = this.nextNoise(random);
    final int red = DitherUtils.clamp(((argb >> 16) & 0xFF) + redNoise);
    final int green = DitherUtils.clamp(((argb >> 8) & 0xFF) + greenNoise);
    final int blue = DitherUtils.clamp((argb & 0xFF) + blueNoise);
    return DitherUtils.getLookupIndex(red, green, blue);
  }

  private int nextNoise(final RandomNumberProvider random) {
    if (this.weight == 0) {
      return 0;
    }
    return random.nextInt(-this.weight, this.weight + 1);
  }
}
