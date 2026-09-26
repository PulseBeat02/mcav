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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Receiver;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/**
 * Round trips through the encoder and the decoder on generated pictures. MCV2 is a rate-distortion codec with no fixed
 * error bound, so the bound a round trip is held to is the encoder's own picture, the one it keeps as the reference of
 * its next frame: a client's decoder must reproduce it bit for bit, frame after frame, whatever the profile, lambda,
 * size and content, or encoder and client drift apart. The encoders verify, so the pictures their searches built leaf
 * by leaf must agree with the decode too: the reference of the shipped profiles is a decode of the chosen frame, and
 * without the check it would agree with the client's by construction. Where the format is lossless - a solid colour
 * is stored as eight-bit RGB, the cheapest leaf a keyframe has - a flat picture comes back exactly.
 */
final class Mcv2RoundTripPropertyTest {

  private static final String SEED = "20260926";
  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();
  private static final List<EncoderSettings> PROFILES = List.of(EncoderSettings.SHIP, EncoderSettings.LOW_BANDWIDTH, EncoderSettings.LIVE);

  /** A picture of flat rectangles, noise and ramps, the kinds of content different leaf modes win on. */
  private static byte[] picture(final Random random, final int width, final int height) {
    final byte[] rgb = new byte[width * height * 3];
    for (int i = 0; i < rgb.length; i++) {
      rgb[i] = (byte) random.nextInt(256);
    }
    for (int shapes = random.nextInt(6); shapes > 0; shapes--) {
      final int x0 = random.nextInt(width);
      final int y0 = random.nextInt(height);
      final int x1 = x0 + 1 + random.nextInt(width - x0);
      final int y1 = y0 + 1 + random.nextInt(height - y0);
      final int kind = random.nextInt(3);
      final int color = random.nextInt(0x1000000);
      for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
          final int at = (y * width + x) * 3;
          for (int channel = 0; channel < 3; channel++) {
            final int base = (color >> (8 * channel)) & 0xFF;
            rgb[at + channel] = (byte) switch (kind) {
              case 0 -> base;
              case 1 -> (base + (x - x0) * 3 + (y - y0)) & 0xFF;
              default -> ((x + y) & 1) == 0 ? base : 255 - base;
            };
          }
        }
      }
    }
    return rgb;
  }

  /** The next picture: the same one, moved a few pixels, partly repainted, or a new scene. */
  private static byte[] next(final Random random, final byte[] previous, final int width, final int height) {
    final int kind = random.nextInt(4);
    if (kind == 3) {
      return picture(random, width, height);
    }
    final byte[] rgb = previous.clone();
    if (kind == 1) {
      final int dx = random.nextInt(9) - 4;
      final int dy = random.nextInt(9) - 4;
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          final int sx = Math.min(width - 1, Math.max(0, x + dx));
          final int sy = Math.min(height - 1, Math.max(0, y + dy));
          System.arraycopy(previous, (sy * width + sx) * 3, rgb, (y * width + x) * 3, 3);
        }
      }
    } else if (kind == 2) {
      final byte[] patch = picture(random, width, height);
      final int rows = 1 + random.nextInt(height);
      System.arraycopy(patch, 0, rgb, 0, rows * width * 3);
    }
    return rgb;
  }

  @Property(seed = SEED, tries = 60)
  void decodesToThePictureTheEncoderKeeps(
    @ForAll @IntRange(min = 1, max = 96) final int width,
    @ForAll @IntRange(min = 1, max = 72) final int height,
    @ForAll @IntRange(min = 0, max = 2) final int profile,
    @ForAll @DoubleRange(min = 5, max = 400) final double lambda,
    @ForAll final long seed
  ) throws Mcv2Exception {
    final Random random = new Random(seed);
    final Mcv2Encoder encoder = new Mcv2Encoder(PROFILES.get(profile).withLambda(lambda), POOL, 2, true);
    final Mcv2Receiver client = new Mcv2Receiver();
    byte[] rgb = picture(random, width, height);
    for (int frame = 0; frame < 4; frame++) {
      final byte[] data = encoder.encode(rgb, width, height, frame);
      assertArrayEquals(encoder.getReference(), client.accept(data));
      rgb = next(random, rgb, width, height);
    }
  }

  @Property(seed = SEED, tries = 60)
  void flatKeyframesComeBackExactly(
    @ForAll @IntRange(min = 1, max = 130) final int width,
    @ForAll @IntRange(min = 1, max = 100) final int height,
    @ForAll @IntRange(min = 0, max = 2) final int profile,
    @ForAll @DoubleRange(min = 5, max = 400) final double lambda,
    @ForAll @IntRange(min = 0, max = 0xFFFFFF) final int color
  ) throws Mcv2Exception {
    final byte[] rgb = new byte[width * height * 3];
    for (int i = 0; i < rgb.length; i += 3) {
      rgb[i] = (byte) (color >> 16);
      rgb[i + 1] = (byte) (color >> 8);
      rgb[i + 2] = (byte) color;
    }
    final Mcv2Encoder encoder = new Mcv2Encoder(PROFILES.get(profile).withLambda(lambda), POOL, 2, false);
    assertArrayEquals(rgb, new Mcv2Receiver().accept(encoder.encode(rgb, width, height, 0)));
  }
}
