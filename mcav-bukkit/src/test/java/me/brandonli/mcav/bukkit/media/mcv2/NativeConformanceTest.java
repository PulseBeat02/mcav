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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.NativeTesting.NativeKernels;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Native encoders reproduce the Java bytes on every fixture at every level this processor runs. */
final class NativeConformanceTest {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  private static Stream<Arguments> levels() {
    return NativeTesting.levels().stream().map(Arguments::of);
  }

  private static Stream<Arguments> levelsAndStreams() {
    return streams("edge");
  }

  private static Stream<Arguments> levelsAndConformanceStreams() {
    return streams("conformance");
  }

  private static Stream<Arguments> streams(final String folder) {
    final List<Arguments> arguments = new ArrayList<>();
    for (final Level level : NativeTesting.levels()) {
      for (final String stream : Mcv2Fixtures.digests(folder).keySet()) {
        arguments.add(Arguments.of(level, stream));
      }
    }
    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void keepsNoFrameOnceItIsEncoded(final Level level) {
    final Supplier<?> factory = NativeTesting.factory(level);
    final List<NativeKernels> made = new CopyOnWriteArrayList<>();
    final MCV2 encoder = NativeTesting.encoder(Settings.DEFAULT, 2, false, () -> {
      final Object kernels = factory.get();
      made.add(NativeTesting.view(kernels));
      return kernels;
    });
    final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
    final int frameBytes = WIDTH * HEIGHT * 3;
    for (int frameIndex = 0; frameIndex < 2; frameIndex++) {
      final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
      encoder.encode(rgb, WIDTH, HEIGHT, frameIndex);
      assertFalse(made.isEmpty());
      assertTrue(made.stream().noneMatch(kernels -> kernels.keeps(rgb)), "frame " + frameIndex);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void encodesEverySourceFixtureAsJavaDoes(final Level level) {
    final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
    final int frameBytes = WIDTH * HEIGHT * 3;
    assertEquals(4 * frameBytes, source.length);
    for (final Settings settings : List.of(Settings.DEFAULT, Settings.FAST)) {
      for (int threads = 1; threads <= 4; threads++) {
        final MCV2 java = NativeTesting.encoder(settings, threads, true, NativeTesting.javaFactory());
        final MCV2 other = NativeTesting.encoder(settings, threads, true, NativeTesting.factory(level));
        for (int frameIndex = 0; frameIndex < 4; frameIndex++) {
          final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
          final String context = settings + " frame " + frameIndex + " threads " + threads + " level " + level;
          assertArrayEquals(java.encode(rgb, WIDTH, HEIGHT, frameIndex), other.encode(rgb, WIDTH, HEIGHT, frameIndex), context);
          assertArrayEquals(java.getReference(), other.getReference(), context);
        }
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void pinsTheOutputOfTheLiveProfiles(final Level level) {
    final byte[] source = Mcv2Fixtures.read("encoder/crop-320x180x4.rgb");
    final int frameBytes = WIDTH * HEIGHT * 3;
    for (final String stream : List.of("crop-default.mcs", "crop-fast.mcs")) {
      final Settings settings = stream.equals("crop-default.mcs") ? Settings.DEFAULT : Settings.FAST;
      final List<byte[]> expected = Mcv2Fixtures.frames(Mcv2Fixtures.read("encoder/" + stream));
      assertEquals(expected.size() * frameBytes, source.length);
      for (int threads = 1; threads <= 4; threads++) {
        final MCV2 java = NativeTesting.encoder(settings, threads, true, NativeTesting.javaFactory());
        final MCV2 other = NativeTesting.encoder(settings, threads, true, NativeTesting.factory(level));
        for (int frameIndex = 0; frameIndex < expected.size(); frameIndex++) {
          final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
          final String context = stream + " frame " + frameIndex + " threads " + threads + " level " + level;
          assertArrayEquals(expected.get(frameIndex), java.encode(rgb, WIDTH, HEIGHT, frameIndex), context);
          assertArrayEquals(expected.get(frameIndex), other.encode(rgb, WIDTH, HEIGHT, frameIndex), context);
          assertArrayEquals(java.getReference(), other.getReference(), context);
        }
      }
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("levelsAndStreams")
  void reencodesTheEdgeCorpusAsJavaDoes(final Level level, final String stream) throws Mcv2Exception {
    reencode(level, "edge/" + stream);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("levelsAndConformanceStreams")
  void reencodesTheConformanceCorpusAsJavaDoes(final Level level, final String stream) throws Mcv2Exception {
    reencode(level, "conformance/" + stream);
  }

  private static void reencode(final Level level, final String stream) throws Mcv2Exception {
    final List<byte[]> frames = Mcv2Fixtures.frames(Mcv2Fixtures.read(stream));
    for (final Settings settings : List.of(Settings.DEFAULT, Settings.FAST)) {
      final Mcv2Receiver receiver = new Mcv2Receiver();
      final MCV2 java = NativeTesting.encoder(settings, 2, true, NativeTesting.javaFactory());
      final MCV2 other = NativeTesting.encoder(settings, 3, true, NativeTesting.factory(level));
      for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
        final Frame frame = Mcv2Decoder.parse(frames.get(frameIndex));
        final byte[] picture = receiver.accept(frames.get(frameIndex));
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final String context = stream + " frame " + frameIndex + " settings " + settings;
        assertArrayEquals(java.encode(picture, width, height, frameIndex), other.encode(picture, width, height, frameIndex), context);
        assertArrayEquals(java.getReference(), other.getReference(), context);
      }
    }
  }
}
