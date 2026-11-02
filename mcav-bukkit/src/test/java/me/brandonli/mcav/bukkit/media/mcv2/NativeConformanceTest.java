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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.NativeTesting.NativeKernels;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class NativeConformanceTest {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 180;

  private static final String CROP_SOURCE_PATH = "encoder/crop-320x180x4.rgb";
  private static final String CROP_DEFAULT_STREAM = "crop-default.mcs";
  private static final String CROP_FAST_STREAM = "crop-fast.mcs";

  private static final String ENCODER_FOLDER = "encoder/";
  private static final String EDGE_FOLDER = "edge/";
  private static final String CONFORMANCE_FOLDER = "conformance/";

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
      for (final String stream : Mcv2Fixtures.readDigests(folder).keySet()) {
        arguments.add(Arguments.of(level, stream));
      }
    }

    return arguments.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void shouldKeepNoFrameOnceItIsEncoded(final Level level) {
    final Supplier<?> factory = NativeTesting.factory(level);
    final List<NativeKernels> made = new CopyOnWriteArrayList<>();
    final Mcv2 encoder = NativeTesting.encoder(Settings.DEFAULT, 2, Mcv2.Verification.UNVERIFIED, () -> {
      final Object kernels = factory.get();
      made.add(NativeTesting.view(kernels));
      return kernels;
    });
    final byte[] source = Mcv2Fixtures.read(CROP_SOURCE_PATH);
    final int frameBytes = WIDTH * HEIGHT * 3;
    for (int frameIndex = 0; frameIndex < 2; frameIndex++) {
      final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
      encoder.encode(rgb, WIDTH, HEIGHT, frameIndex);
      final boolean noneMade = made.isEmpty();
      assertFalse(noneMade);
      final boolean keepsNoFrame = made.stream().noneMatch(kernels -> kernels.keeps(rgb));
      assertTrue(keepsNoFrame, "frame " + frameIndex);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void shouldEncodeEverySourceFixtureAsJavaDoes(final Level level) {
    final byte[] source = Mcv2Fixtures.read(CROP_SOURCE_PATH);
    final int frameBytes = WIDTH * HEIGHT * 3;
    final int expectedLength = 4 * frameBytes;
    final int sourceLength = source.length;
    assertEquals(expectedLength, sourceLength);

    for (final Settings settings : List.of(Settings.DEFAULT, Settings.FAST)) {
      for (int threads = 1; threads <= 4; threads++) {
        this.assertEncodesAsJava(level, settings, threads, source);
      }
    }
  }

  private void assertEncodesAsJava(final Level level, final Settings settings, final int threads, final byte[] source) {
    final int frameBytes = WIDTH * HEIGHT * 3;
    final Mcv2 java = NativeTesting.encoder(settings, threads, Mcv2.Verification.VERIFIED, NativeTesting.javaFactory());
    final Mcv2 other = NativeTesting.encoder(settings, threads, Mcv2.Verification.VERIFIED, NativeTesting.factory(level));
    for (int frameIndex = 0; frameIndex < 4; frameIndex++) {
      final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
      final String context = settings + " frame " + frameIndex + " threads " + threads + " level " + level;
      final byte[] expectedEncoded = java.encode(rgb, WIDTH, HEIGHT, frameIndex);
      final byte[] encoded = other.encode(rgb, WIDTH, HEIGHT, frameIndex);
      assertArrayEquals(expectedEncoded, encoded, context);

      final byte[] expectedReference = java.getReference().orElseThrow();
      final byte[] reference = other.getReference().orElseThrow();
      assertArrayEquals(expectedReference, reference, context);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levels")
  void shouldPinTheOutputOfTheLiveProfiles(final Level level) {
    final byte[] source = Mcv2Fixtures.read(CROP_SOURCE_PATH);
    final int frameBytes = WIDTH * HEIGHT * 3;
    for (final String stream : List.of(CROP_DEFAULT_STREAM, CROP_FAST_STREAM)) {
      final Settings settings = stream.equals(CROP_DEFAULT_STREAM) ? Settings.DEFAULT : Settings.FAST;
      final List<byte[]> expected = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(ENCODER_FOLDER + stream));
      final int expectedLength = expected.size() * frameBytes;
      final int sourceLength = source.length;
      assertEquals(expectedLength, sourceLength);
      for (int threads = 1; threads <= 4; threads++) {
        this.assertPinnedOutput(level, settings, threads, source, stream, expected);
      }
    }
  }

  private void assertPinnedOutput(
    final Level level,
    final Settings settings,
    final int threads,
    final byte[] source,
    final String stream,
    final List<byte[]> expected
  ) {
    final int frameBytes = WIDTH * HEIGHT * 3;
    final Mcv2 java = NativeTesting.encoder(settings, threads, Mcv2.Verification.VERIFIED, NativeTesting.javaFactory());
    final Mcv2 other = NativeTesting.encoder(settings, threads, Mcv2.Verification.VERIFIED, NativeTesting.factory(level));
    for (int frameIndex = 0; frameIndex < expected.size(); frameIndex++) {
      final byte[] rgb = Arrays.copyOfRange(source, frameIndex * frameBytes, (frameIndex + 1) * frameBytes);
      final String context = stream + " frame " + frameIndex + " threads " + threads + " level " + level;
      final byte[] expectedFrame = expected.get(frameIndex);
      final byte[] javaEncoded = java.encode(rgb, WIDTH, HEIGHT, frameIndex);
      assertArrayEquals(expectedFrame, javaEncoded, context);

      final byte[] expectedFrameForOther = expected.get(frameIndex);
      final byte[] otherEncoded = other.encode(rgb, WIDTH, HEIGHT, frameIndex);
      assertArrayEquals(expectedFrameForOther, otherEncoded, context);

      final byte[] expectedReference = java.getReference().orElseThrow();
      final byte[] reference = other.getReference().orElseThrow();
      assertArrayEquals(expectedReference, reference, context);
    }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("levelsAndStreams")
  void shouldReencodeTheEdgeCorpusAsJavaDoes(final Level level, final String stream) {
    this.reencode(level, EDGE_FOLDER + stream);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("levelsAndConformanceStreams")
  void shouldReencodeTheConformanceCorpusAsJavaDoes(final Level level, final String stream) {
    this.reencode(level, CONFORMANCE_FOLDER + stream);
  }

  private void reencode(final Level level, final String stream) {
    final List<byte[]> frames = Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(stream));
    for (final Settings settings : List.of(Settings.DEFAULT, Settings.FAST)) {
      final Mcv2Receiver receiver = new Mcv2Receiver();
      final Mcv2 java = NativeTesting.encoder(settings, 2, Mcv2.Verification.VERIFIED, NativeTesting.javaFactory());
      final Mcv2 other = NativeTesting.encoder(settings, 3, Mcv2.Verification.VERIFIED, NativeTesting.factory(level));
      for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
        final Frame frame = Mcv2Decoder.parse(frames.get(frameIndex));
        final byte[] picture = receiver.accept(frames.get(frameIndex));
        final int width = frame.getWidth();
        final int height = frame.getHeight();
        final String context = stream + " frame " + frameIndex + " settings " + settings;
        final byte[] expectedEncoded = java.encode(picture, width, height, frameIndex);
        final byte[] encoded = other.encode(picture, width, height, frameIndex);
        assertArrayEquals(expectedEncoded, encoded, context);
        final byte[] expectedReference = java.getReference().orElseThrow();
        final byte[] reference = other.getReference().orElseThrow();
        assertArrayEquals(expectedReference, reference, context);
      }
    }
  }
}
