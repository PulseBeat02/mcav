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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class Mcv2GoldenTest {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  private static final String CROP_DEFAULT_STREAM = "crop-default.mcs";

  private static final String ENCODER_FOLDER = "encoder/";
  private static final String CROP_SOURCE_PATH = "encoder/crop-320x180x4.rgb";

  @Test
  void shouldPinEightFramesOfThePanningScene() {
    final List<Settings> settings = List.of(Settings.DEFAULT, Settings.FAST);
    final List<String> expected = List.of(
      "65221d9c66059ed6c8178572845687f9548a42235223ca65d6d6f0d29ff38cad",
      "3b87eeea72858c4fe9160be1a7acc1fb6670bc0b7fe47bd8cfae346e70813d83"
    );
    for (int preset = 0; preset < settings.size(); preset++) {
      final Mcv2 encoder = new Mcv2(settings.get(preset), ForkJoinPool.commonPool(), 3, Mcv2.Verification.VERIFIED);
      final ByteArrayOutputStream stream = new ByteArrayOutputStream();
      for (int frame = 0; frame < 8; frame++) {
        stream.writeBytes(encoder.encode(Mcv2Pictures.scene(96, 64, frame, 2), 96, 64, frame));
      }

      final String expectedDigest = expected.get(preset);
      final String digest = Mcv2Fixtures.sha256(stream.toByteArray());
      assertEquals(expectedDigest, digest);
    }
  }

  @ParameterizedTest(name = "{0} with {1} threads, {2}")
  @CsvSource({
    "crop-default.mcs, 1, VERIFIED",
    "crop-default.mcs, 1, UNVERIFIED",
    "crop-default.mcs, 2, VERIFIED",
    "crop-default.mcs, 2, UNVERIFIED",
    "crop-default.mcs, 3, VERIFIED",
    "crop-default.mcs, 3, UNVERIFIED",
    "crop-default.mcs, 4, VERIFIED",
    "crop-default.mcs, 4, UNVERIFIED",
    "crop-fast.mcs, 1, VERIFIED",
    "crop-fast.mcs, 1, UNVERIFIED",
    "crop-fast.mcs, 2, VERIFIED",
    "crop-fast.mcs, 2, UNVERIFIED",
    "crop-fast.mcs, 3, VERIFIED",
    "crop-fast.mcs, 3, UNVERIFIED",
    "crop-fast.mcs, 4, VERIFIED",
    "crop-fast.mcs, 4, UNVERIFIED",
  })
  void shouldReproduceTheGoldenEncoder(final String stream, final int threads, final Mcv2.Verification verification) {
    final Settings settings = stream.equals(CROP_DEFAULT_STREAM) ? Settings.DEFAULT : Settings.FAST;
    final List<byte[]> expected = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(ENCODER_FOLDER + stream));
    final byte[] source = Mcv2Fixtures.read(CROP_SOURCE_PATH);
    final ForkJoinPool pool = new ForkJoinPool(threads);
    try {
      final Mcv2 encoder = new Mcv2(settings, pool, threads, verification);
      final int frameBytes = WIDTH * HEIGHT * 3;
      final int expectedLength = expected.size() * frameBytes;
      final int sourceLength = source.length;
      assertEquals(expectedLength, sourceLength);

      for (int frameIndex = 0; frameIndex < expected.size(); frameIndex++) {
        final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
        final byte[] expectedFrame = expected.get(frameIndex);
        final byte[] frame = encoder.encode(rgb, WIDTH, HEIGHT, frameIndex);
        assertArrayEquals(expectedFrame, frame, stream + " frame " + frameIndex);
      }
    } finally {
      pool.shutdown();
    }
  }
}
