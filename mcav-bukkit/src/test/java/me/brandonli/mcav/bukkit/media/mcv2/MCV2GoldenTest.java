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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Encoder conformance: on a committed 320x180 crop of the 1080p30 source (four frames, a keyframe and three P frames),
 * the encoder reproduces the committed DEFAULT and FAST streams byte for byte, with one to four threads and with the
 * verification on or off.
 */
final class MCV2GoldenTest {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  @Test
  void pinsEightFramesOfThePanningScene() {
    final List<Settings> settings = List.of(Settings.DEFAULT, Settings.FAST);
    final List<String> expected = List.of(
      "65221d9c66059ed6c8178572845687f9548a42235223ca65d6d6f0d29ff38cad",
      "3b87eeea72858c4fe9160be1a7acc1fb6670bc0b7fe47bd8cfae346e70813d83"
    );
    for (int preset = 0; preset < settings.size(); preset++) {
      final MCV2 encoder = new MCV2(settings.get(preset), ForkJoinPool.commonPool(), 3, true);
      final ByteArrayOutputStream stream = new ByteArrayOutputStream();
      for (int frame = 0; frame < 8; frame++) {
        stream.writeBytes(encoder.encode(Mcv2Pictures.scene(96, 64, frame, 2), 96, 64, frame));
      }
      assertEquals(expected.get(preset), Mcv2Fixtures.sha256(stream.toByteArray()));
    }
  }

  @ParameterizedTest(name = "{0} with {1} threads, verify {2}")
  @CsvSource({
    "crop-default.mcs, 1, true",
    "crop-default.mcs, 1, false",
    "crop-default.mcs, 2, true",
    "crop-default.mcs, 2, false",
    "crop-default.mcs, 3, true",
    "crop-default.mcs, 3, false",
    "crop-default.mcs, 4, true",
    "crop-default.mcs, 4, false",
    "crop-fast.mcs, 1, true",
    "crop-fast.mcs, 1, false",
    "crop-fast.mcs, 2, true",
    "crop-fast.mcs, 2, false",
    "crop-fast.mcs, 3, true",
    "crop-fast.mcs, 3, false",
    "crop-fast.mcs, 4, true",
    "crop-fast.mcs, 4, false",
  })
  void reproducesTheGoldenEncoder(final String stream, final int threads, final boolean shouldVerify) {
    final Settings settings = stream.equals("crop-default.mcs") ? Settings.DEFAULT : Settings.FAST;
    final List<byte[]> expected = Mcv2Fixtures.frames(Mcv2Fixtures.read("encoder/" + stream));
    final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
    final ForkJoinPool pool = new ForkJoinPool(threads);
    try {
      final MCV2 encoder = new MCV2(settings, pool, threads, shouldVerify);
      final int frameBytes = WIDTH * HEIGHT * 3;
      assertEquals(expected.size() * frameBytes, source.length);
      for (int frameIndex = 0; frameIndex < expected.size(); frameIndex++) {
        final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
        assertArrayEquals(expected.get(frameIndex), encoder.encode(rgb, WIDTH, HEIGHT, frameIndex), stream + " frame " + frameIndex);
      }
    } finally {
      pool.shutdown();
    }
  }
}
