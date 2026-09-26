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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Encoder conformance: on a committed 320x180 crop of the 1080p30 source (four frames, a keyframe and three P frames),
 * the encoder reproduces the Python reference encoder's streams byte for byte at both shipped lambdas, for any thread
 * count and with the verification on or off.
 *
 * <p>The full-size check (30 frames of the 1920x1080 source at both lambdas, identical to the frontier's recorded
 * streams) runs outside the tests, with {@code tools/mcv2/encode-check.sh}; its result is in the stage report.
 */
final class EncoderConformanceTest {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  @ParameterizedTest(name = "{0} with {1} threads, verify {2}")
  @CsvSource({ "crop-ship.mcs, 1, true", "crop-ship.mcs, 3, false", "crop-low.mcs, 2, true", "crop-low.mcs, 4, false" })
  void reproducesTheReferenceEncoder(final String stream, final int threads, final boolean verify) {
    final EncoderSettings settings = stream.equals("crop-ship.mcs") ? EncoderSettings.SHIP : EncoderSettings.LOW_BANDWIDTH;
    final List<byte[]> expected = Mcv2Fixtures.frames(Mcv2Fixtures.read("encoder/" + stream));
    final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
    final ForkJoinPool pool = new ForkJoinPool(threads);
    try {
      final Mcv2Encoder encoder = new Mcv2Encoder(settings, pool, threads, verify);
      final int frameBytes = WIDTH * HEIGHT * 3;
      assertEquals(expected.size() * frameBytes, source.length);
      for (int i = 0; i < expected.size(); i++) {
        final byte[] rgb = Arrays.copyOfRange(source, i * frameBytes, (i + 1) * frameBytes);
        assertArrayEquals(expected.get(i), encoder.encode(rgb, WIDTH, HEIGHT, i), stream + " frame " + i);
      }
    } finally {
      pool.shutdown();
    }
  }
}
