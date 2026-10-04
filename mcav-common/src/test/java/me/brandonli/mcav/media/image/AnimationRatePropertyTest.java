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
package me.brandonli.mcav.media.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

final class AnimationRatePropertyTest {

  private static final String SEED = "20261003";

  @Provide
  Arbitrary<Double> unusableReportedRates() {
    return Arbitraries.of(
      Double.MIN_VALUE,
      Double.MAX_VALUE,
      (double) Float.MIN_VALUE / 4,
      (double) Float.MAX_VALUE * 2,
      0.0,
      -1.0,
      Double.NaN,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    );
  }

  @Property(seed = SEED)
  void unusableRatesUseTheDecodedTimestamps(
    @ForAll("unusableReportedRates") final double reported,
    @ForAll @IntRange(min = 2, max = 101) final int frames
  ) {
    final long first = 1_000_000L;
    final long last = first + (frames - 1L) * 40_000L;
    final float rate = DynamicImageBufferImpl.resolveFrameRate(reported, frames, first, last);
    assertEquals(25.0f, rate, "the timestamps show one frame every 40 milliseconds");
  }

  @Property(seed = SEED)
  void noUsableRateOrTimestampStillProducesAPlayableDefault(@ForAll("unusableReportedRates") final double reported) {
    final float rate = DynamicImageBufferImpl.resolveFrameRate(reported, 1, 0, 0);
    assertEquals(10.0f, rate);
    assertTrue(rate > 0 && Float.isFinite(rate));
  }

  @Provide
  Arbitrary<Float> representableReportedRates() {
    return Arbitraries.of(Float.MIN_VALUE, Float.MIN_NORMAL, 1.0f, 24.0f, 29.97f, 60.0f, Float.MAX_VALUE);
  }

  @Property(seed = SEED)
  void everyRepresentableRateIsRetained(@ForAll("representableReportedRates") final float reported) {
    final float rate = DynamicImageBufferImpl.resolveFrameRate(reported, 3, 0, 80_000L);
    assertEquals(reported, rate);
  }
}
