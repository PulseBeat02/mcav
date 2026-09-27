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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.Mcv2Receiver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The conformance of the native kernels, at every level this processor runs: through them the reference search still
 * writes the Python reference encoder's streams byte for byte, the live profile still writes its pinned output, and
 * every picture of the edge corpus - random trees of every leaf mode, class and index form, at odd and cropped sizes -
 * re-encodes exactly as with the Java kernels.
 */
final class NativeConformanceTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  static Stream<Arguments> levels() {
    return NativeTesting.levels().stream().map(Arguments::of);
  }

  static Stream<Arguments> levelsAndStreams() {
    final List<Arguments> arguments = new ArrayList<>();
    for (final NativeKernels.Level level : NativeTesting.levels()) {
      for (final String stream : Mcv2Fixtures.digests("edge").keySet()) {
        arguments.add(Arguments.of(level, stream));
      }
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void reproducesTheReferenceEncoder(final NativeKernels.Level level) {
    for (final String stream : List.of("crop-ship.mcs", "crop-low.mcs")) {
      final EncoderSettings settings = stream.equals("crop-ship.mcs") ? EncoderSettings.SHIP : EncoderSettings.LOW_BANDWIDTH;
      final List<byte[]> expected = Mcv2Fixtures.frames(Mcv2Fixtures.read("encoder/" + stream));
      final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
      final Mcv2Encoder encoder = new Mcv2Encoder(settings, POOL, 3, true, NativeTesting.factory(level));
      final int frameBytes = WIDTH * HEIGHT * 3;
      for (int i = 0; i < expected.size(); i++) {
        final byte[] rgb = Arrays.copyOfRange(source, i * frameBytes, (i + 1) * frameBytes);
        assertArrayEquals(expected.get(i), encoder.encode(rgb, WIDTH, HEIGHT, i), stream + " frame " + i);
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void pinsTheOutputOfTheLiveProfile(final NativeKernels.Level level) throws NoSuchAlgorithmException {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, false, NativeTesting.factory(level));
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < 8; i++) {
      digest.update(encoder.encode(LiveEncoderTest.scene(96, 64, i, 2), 96, 64, i));
    }
    assertEquals(LiveEncoderTest.LIVE_DIGEST, HexFormat.of().formatHex(digest.digest()));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("levelsAndStreams")
  void reencodesTheEdgeCorpusAsJavaDoes(final NativeKernels.Level level, final String stream) throws Mcv2Exception {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final Mcv2Encoder java = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true, JavaKernels.FACTORY);
    final Mcv2Encoder other = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true, NativeTesting.factory(level));
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read("edge/" + stream));
    for (int i = 0; i < frames.size(); i++) {
      final Mcv2Frame frame = FrameParser.parse(frames.get(i));
      final byte[] picture = receiver.accept(frames.get(i));
      final int width = frame.getWidth();
      final int height = frame.getHeight();
      assertArrayEquals(java.encode(picture, width, height, i), other.encode(picture, width, height, i), stream + " frame " + i);
    }
  }
}
