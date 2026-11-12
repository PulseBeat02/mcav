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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.NativeTesting.NativeKernels;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

final class NativeKernelsTest {

  private static final int SHORT_FIT_LENGTH = 15;

  private static final int MAXIMUM_MOTION = 1 << 20;

  private static final int MAXIMUM_BLOCK_SIZE = 32;

  private static final int MINIMUM_VECTOR_COMPONENT = -128;

  private static final int MAXIMUM_CHANNEL_VALUE = 255;

  // every call fails, so a missed argument check shows as the wrong exception
  private NativeKernels failing() {
    return NativeTesting.kernels(new Binding(Level.SCALAR, (name, descriptor) -> this.returning(descriptor, null)));
  }

  private MethodHandle returning(final FunctionDescriptor descriptor, final @Nullable Object value) {
    final MethodType type = descriptor.toMethodType();
    final MethodHandle body;
    if (value == null) {
      body = MethodHandles.insertArguments(
        MethodHandles.throwException(type.returnType(), IllegalStateException.class),
        0,
        new IllegalStateException("the library failed!")
      );
    } else {
      body = MethodHandles.constant(type.returnType(), value);
    }

    return MethodHandles.dropArguments(body, 0, type.parameterList());
  }

  private void refused(final Executable call) {
    assertThrows(IllegalArgumentException.class, call);
  }

  @Test
  void shouldRefuseShortContiguousFitArrays() {
    final NativeKernels kernels = this.failing();
    this.refused(() -> kernels.fit(new float[8 * 8 - 1], 8, new float[16]));
    this.refused(() -> kernels.fit(new float[8 * 8], 8, new float[SHORT_FIT_LENGTH]));
  }

  private void failed(final Executable call) {
    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, call);
    final String message = exception.getMessage();
    assertEquals("MCV2 native kernel failed!", message);
  }

  @Test
  void shouldForgetTheArraysItWasPassed() {
    final NativeKernels kernels = this.failing();
    final byte[] picture = new byte[16 * 16 * 3];
    final int[] block = this.allocateBlockOutsideTheCacheSlotOf(picture);
    this.failed(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    final boolean keepsPicture = kernels.keeps(picture);
    assertTrue(keepsPicture, "a coder passes the same picture block after block");

    kernels.forgetArrays();
    final boolean keepsPictureAfterForgetting = kernels.keeps(picture);
    assertFalse(keepsPictureAfterForgetting);

    final boolean keepsBlock = kernels.keeps(block);
    assertFalse(keepsBlock);
  }

  private int[] allocateBlockOutsideTheCacheSlotOf(final byte[] picture) {
    final int pictureSlot = this.findCacheSlot(picture);
    int[] block = new int[8 * 8 * 3];
    while (this.findCacheSlot(block) == pictureSlot) {
      block = new int[8 * 8 * 3];
    }

    return block;
  }

  private int findCacheSlot(final Object array) {
    final Class<?> type = Mcv2Internals.nested("NativeKernels");
    return (int) Mcv2Internals.callForResult(type, null, "slot", new Class<?>[] { Object.class }, array);
  }

  @Test
  void shouldReleaseTheSegmentsThatKeepFrameArraysReachable() {
    final Class<?> type = Mcv2Internals.nested("NativeKernels");
    final Object target = Mcv2Internals.construct(
      type,
      new Class<?>[] { Binding.class },
      new Binding(Level.SCALAR, (name, descriptor) -> this.returning(descriptor, null))
    );
    final NativeKernels kernels = NativeTesting.view(target);
    this.failed(() -> kernels.predict(new byte[16 * 16 * 3], 16, 16, 0, 0, 8, 0, 0, new int[8 * 8 * 3]));
    final Object[] segments = (Object[]) Mcv2Internals.field(type, target, "cachedSegments");
    final boolean keepsASegment = Arrays.stream(segments).anyMatch(Objects::nonNull);
    assertTrue(keepsASegment);

    kernels.forgetArrays();
    for (final Object segment : segments) {
      assertNull(segment);
    }
  }

  @Test
  void shouldCheckEveryIndexBeforeTheCall() {
    final NativeKernels kernels = this.failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    this.refused(() -> kernels.predicted(block, 4, block));
    this.refused(() -> kernels.predicted(block, 12, block));
    this.refused(() -> kernels.predicted(block, 64, block));
    this.refused(() -> kernels.predicted(block, 8, small));
    this.refused(() -> kernels.predicted(small, 8, block));
    kernels.start(small, 0, 1);
    this.refused(() -> kernels.solid(0, 8, block));
    kernels.start(block, 0, 1);
    this.refused(() -> kernels.palette(new byte[13], 8, block));
    this.refused(() -> kernels.compact(small, record, 0, 8, block));
    this.refused(() -> kernels.compact(block, new byte[9], 0, 8, block));
    // pictures, and coordinates whose sums could overflow
    this.refused(() -> kernels.predict(picture, 0, 16, 0, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 4097, 16, 0, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 0, 0, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 4097, 0, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 17, 16, 0, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, small));
    this.refused(() -> kernels.predict(picture, 16, 16, -MAXIMUM_MOTION - 1, 0, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 16, 0, MAXIMUM_MOTION + 1, 8, 0, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 1 << 21, 0, block));
    this.refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, -(1 << 21), block));
    this.refused(() -> kernels.fit(new float[63], 8, floats));
    this.refused(() -> kernels.fit(floats, 8, new float[3]));
    this.refused(() -> kernels.cluster(block, 8, new int[5]));
    this.refused(() -> kernels.finishPalette(block, -1, new int[6], new int[6], new byte[64]));
    this.refused(() ->
      kernels.finishPalette(
        new int[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3 + 3],
        MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE + 1,
        new int[6],
        new int[6],
        new byte[1025]
      )
    );
    this.refused(() -> kernels.finishPalette(small, 64, new int[6], new int[6], new byte[64]));
    this.refused(() -> kernels.finishPalette(block, 64, new int[6], new int[6], new byte[63]));
    this.refused(() -> kernels.finishPattern(small, 8, new int[6], new int[6], new byte[64]));
    this.refused(() -> kernels.finishPattern(block, 8, new int[6], new int[6], new byte[63]));
    this.refused(() -> kernels.seeded(picture, 17, 16, block, 0, 0, 8, 4, new int[0]));
    this.refused(() -> kernels.seeded(picture, 16, 16, small, 0, 0, 8, 4, new int[0]));
    this.refused(() -> kernels.seeded(picture, 16, 16, block, 1 << 21, 0, 8, 4, new int[0]));
    this.refused(() -> kernels.seeded(picture, 16, 16, block, 0, 1 << 21, 8, 4, new int[0]));
    this.refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, -1, new int[0]));
    this.refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, MAXIMUM_MOTION + 1, new int[0]));
    this.refused(() -> kernels.loadSource(picture, 16, 16, -1, 0, 8, block));
    this.refused(() -> kernels.loadSource(picture, 16, 16, 16, 0, 8, block));
    this.refused(() -> kernels.loadSource(picture, 16, 16, 0, -1, 8, block));
    this.refused(() -> kernels.loadSource(picture, 16, 16, 0, 16, 8, block));
    this.refused(() -> kernels.loadSource(picture, 16, 16, 0, 0, 8, small));
    this.refused(() -> kernels.loadSource(new byte[3], 16, 16, 0, 0, 8, block));
    this.refused(() -> kernels.halve(block, 0, block));
    this.refused(() -> kernels.halve(block, 7, block));
    this.refused(() -> kernels.halve(new int[64 * 64 * 3], 64, block));
    this.refused(() -> kernels.halve(small, 8, block));
    this.refused(() -> kernels.halve(block, 8, new int[4 * 4 * 3 - 1]));
    this.refused(() -> kernels.residualTarget(block, block, -1, floats));
    this.refused(() ->
      kernels.residualTarget(
        new int[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3 + 3],
        new int[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3 + 3],
        MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE + 1,
        new float[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3 + 3]
      )
    );
    this.refused(() -> kernels.residualTarget(new int[10], block, 64, floats));
    this.refused(() -> kernels.residualTarget(block, small, 64, floats));
    this.refused(() -> kernels.residualTarget(block, block, 64, new float[10]));
  }

  @Test
  void shouldRefuseEachBadArgumentInTheKernelThatTakesIt() {
    // every other argument is valid, so a kernel that skipped the check would reach the failing library instead
    final NativeKernels kernels = this.failing();
    final int[] big = new int[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3];
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[4096];
    final float[] floats = new float[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3];
    final float[] shortFloats = new float[8 * 8 - 1];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(big, 0, 1);
    this.refused(() -> kernels.solid(0, 12, big));
    this.refused(() -> kernels.palette(record, 12, big));
    this.refused(() -> kernels.compact(big, record, 0, 12, big));
    this.refused(() -> kernels.predict(picture, 16, 16, 0, 0, 12, 0, 0, big));
    this.refused(() -> kernels.fit(floats, 12, floats));
    this.refused(() -> kernels.cluster(big, 12, new int[6]));
    this.refused(() -> kernels.finishPattern(big, 12, new int[6], new int[6], new byte[1024]));
    this.refused(() -> kernels.seeded(picture, 16, 16, big, 0, 0, 12, 4, new int[0]));
    this.refused(() -> kernels.loadSource(picture, 16, 16, 0, 0, 12, big));
    this.refused(() -> kernels.cluster(small, 8, new int[6]));
    this.refused(() -> kernels.fit(floats, 8, new float[SHORT_FIT_LENGTH]));
    this.refused(() -> kernels.residualTarget(small, block, 64, floats));
    this.refused(() -> kernels.residualTarget(block, block, 64, shortFloats));
  }

  @Test
  void shouldPassEveryLimitItselfToTheLibrary() {
    // a comparison one step too strict would refuse the limit instead of reaching the failing library
    final NativeKernels kernels = this.failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] big = new int[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3];
    final float[] floats = new float[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE * 3];
    final byte[] line = new byte[4096 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    this.failed(() -> kernels.predict(line, 1, 4096, 0, 0, 8, 0, 0, block));
    this.failed(() -> kernels.predict(line, 4096, 1, 0, 0, 8, 0, 0, block));
    this.failed(() -> kernels.predict(picture, 16, 16, -MAXIMUM_MOTION, MAXIMUM_MOTION, 8, -MAXIMUM_MOTION, MAXIMUM_MOTION, block));
    this.failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, new int[0]));
    this.failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, MAXIMUM_MOTION, new int[0]));
    this.failed(() -> kernels.finishPalette(big, 0, new int[6], new int[6], new byte[0]));
    this.failed(() ->
      kernels.finishPalette(
        big,
        MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE,
        new int[6],
        new int[6],
        new byte[MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE]
      )
    );
    this.failed(() -> kernels.halve(new int[2 * 2 * 3], 2, new int[3]));
    this.failed(() -> kernels.halve(big, MAXIMUM_BLOCK_SIZE, new int[16 * 16 * 3]));
    this.failed(() -> kernels.residualTarget(big, big, 0, floats));
    this.failed(() -> kernels.residualTarget(big, big, MAXIMUM_BLOCK_SIZE * MAXIMUM_BLOCK_SIZE, floats));
  }

  @Test
  void shouldReportAKernelThatFails() {
    final NativeKernels kernels = this.failing();
    final int[] block = new int[8 * 8 * 3];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    this.failed(() -> kernels.predicted(block, 8, block));
    this.failed(() -> kernels.solid(0, 8, block));
    this.failed(() -> kernels.palette(record, 8, block));
    this.failed(() -> kernels.compact(block, record, 0, 8, block));
    this.failed(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    this.failed(() -> kernels.fit(floats, 8, floats));
    this.failed(() -> kernels.cluster(block, 8, new int[6]));
    this.failed(() -> kernels.finishPalette(block, 64, new int[6], new int[6], new byte[64]));
    this.failed(() -> kernels.finishPattern(block, 8, new int[6], new int[6], new byte[64]));
    this.failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 4, new int[0]));
    this.failed(() -> kernels.loadSource(picture, 16, 16, 0, 0, 8, block));
    this.failed(() -> kernels.halve(block, 8, new int[4 * 4 * 3]));
    this.failed(() -> kernels.residualTarget(block, block, 64, floats));
  }

  @Test
  void shouldKeepTheDistortionOfAFinishedBlockOnly() {
    final NativeKernels stopped = NativeTesting.kernels(
      new Binding(Level.SCALAR, (name, descriptor) -> this.returning(descriptor, name.equals("predicted") ? -1L : null))
    );
    final NativeKernels finished = NativeTesting.kernels(
      new Binding(Level.SCALAR, (name, descriptor) -> this.returning(descriptor, name.equals("predicted") ? 42L : null))
    );
    final int[] block = new int[8 * 8 * 3];
    finished.start(block, 0, 1);
    final boolean finishedPredicted = finished.predicted(block, 8, block);
    assertTrue(finishedPredicted);

    final long finishedDistortion = finished.getDistortion();
    assertEquals(42, finishedDistortion);

    stopped.start(block, 0, 1);
    final boolean stoppedPredicted = stopped.predicted(block, 8, block);
    assertFalse(stoppedPredicted);

    final long stoppedDistortion = stopped.getDistortion();
    assertEquals(0, stoppedDistortion);

    final Level level = finished.getLevel();
    assertEquals(Level.SCALAR, level);

    final String symbol = Level.AVX2.getSymbol();
    assertEquals("avx2", symbol);
  }

  @Test
  void shouldComputeEveryKernelAsJavaDoesAtEveryLevel() {
    for (final Level level : NativeTesting.levels()) {
      final NativeKernels kernels = NativeTesting.kernels(level);
      for (int kernel = 0; kernel < KernelDifferential.KERNELS; kernel++) {
        this.assertKernelMatchesJava(level, kernels, kernel);
      }
    }
  }

  private void assertKernelMatchesJava(final Level level, final NativeKernels kernels, final int kernel) {
    for (int seed = 0; seed < 12; seed++) {
      final KernelDifferential.Values values = KernelDifferential.of(new Random(seed * 31L + kernel));
      final @Nullable String difference = KernelDifferential.compare(values, Mcv2Internals.javaKernels(), kernels, kernel);
      assertNull(difference, "%s kernel %s seed %s".formatted(level, kernel, seed));
    }
  }

  @Test
  void shouldCheckCompactQuantizersAndExactRecordBoundaries() {
    final NativeKernels kernels = this.failing();
    final int[] block = new int[8 * 8 * 3];
    kernels.start(block, 0, 1);
    final byte[] record = new byte[Mcv2Decoder.COMPACT_BYTES];
    this.refused(() -> kernels.compact(block, record, -1, 8, block));
    this.refused(() -> kernels.compact(block, record, 3, 8, block));
    this.refused(() -> kernels.compact(block, new byte[Mcv2Decoder.COMPACT_BYTES - 1], 0, 8, block));
    this.failed(() -> kernels.compact(block, record, 0, 8, block));
    this.failed(() -> kernels.compact(block, record, 2, 8, block));
  }

  @Test
  void shouldRefuseShortPaletteColorArraysBeforeDispatch() {
    final NativeKernels kernels = this.failing();
    final int[] block = new int[8 * 8 * 3];
    this.refused(() -> kernels.finishPalette(block, 64, new int[6], new int[5], new byte[64]));
    this.refused(() -> kernels.finishPattern(block, 8, new int[6], new int[5], new byte[64]));
  }

  @Test
  void shouldReconstructTheFixedLumaRecordAtEveryQuantizer() {
    final int[] source = new int[8 * 8 * 3];
    final int[] prediction = new int[source.length];
    Arrays.fill(source, 128);
    Arrays.fill(prediction, 128);
    final byte[] record = new byte[10];
    Arrays.fill(record, (byte) 0xdd);
    record[0] = 127;
    record[1] = MINIMUM_VECTOR_COMPONENT;
    for (final Level level : NativeTesting.levels()) {
      final NativeKernels kernels = NativeTesting.kernels(level);
      for (int quantizer = 0; quantizer <= 2; quantizer++) {
        final int[] expected = new int[source.length];
        Arrays.fill(expected, 128 - (3 << quantizer));
        final int[] actual = new int[source.length];
        kernels.start(source, 0, Double.POSITIVE_INFINITY);
        final boolean finished = kernels.compact(prediction, record, quantizer, 8, actual);
        assertTrue(finished);
        assertArrayEquals(expected, actual, "%s q=%s".formatted(level, quantizer));
      }
    }
  }

  @Test
  void shouldComputeContiguousLumaResidualsIncludingTheVectorTail() {
    final int[] source = new int[17 * 3];
    final int[] prediction = new int[source.length];
    final float[] expected = new float[18];
    for (int pixel = 0; pixel < 17; pixel++) {
      final int offset = pixel * 3;
      if (pixel % 3 == 0) {
        source[offset + 1] = MAXIMUM_CHANNEL_VALUE;
        expected[pixel] = 127.5f;
      } else if (pixel % 3 == 1) {
        source[offset] = MAXIMUM_CHANNEL_VALUE;
        Arrays.fill(prediction, offset, offset + 3, MAXIMUM_CHANNEL_VALUE);
        expected[pixel] = -191.25f;
      } else {
        source[offset + 2] = MAXIMUM_CHANNEL_VALUE;
        prediction[offset + 2] = MAXIMUM_CHANNEL_VALUE;
      }
    }

    expected[17] = 999;
    for (final Level level : NativeTesting.levels()) {
      final float[] actual = new float[18];
      Arrays.fill(actual, 999);
      NativeTesting.kernels(level).residualTarget(source, prediction, 17, actual);
      assertArrayEquals(expected, actual, level.getSymbol());
    }
  }

  @Test
  void shouldPredictUnscaledBytesAndKeepIntegerPaletteMeans() {
    for (final Level level : NativeTesting.levels()) {
      final NativeKernels kernels = NativeTesting.kernels(level);
      final byte[] reference = new byte[8 * 8 * 3];
      final int[] expected = new int[reference.length];
      for (int index = 0; index < reference.length; index++) {
        expected[index] = (index * 37) & MAXIMUM_CHANNEL_VALUE;
        reference[index] = (byte) expected[index];
      }
      final int[] prediction = new int[reference.length];
      kernels.predict(reference, 8, 8, 0, 0, 8, 0, 0, prediction);
      assertArrayEquals(expected, prediction, level.getSymbol());
      final int[] actual = new int[reference.length];
      kernels.start(expected, 0, Double.POSITIVE_INFINITY);
      final boolean finished = kernels.predicted(prediction, 8, actual);
      assertTrue(finished);
      assertArrayEquals(expected, actual, level.getSymbol());
      final long distortion = kernels.getDistortion();
      assertEquals(0, distortion);

      kernels.predict(new byte[] { 0, 127, -1 }, 1, 1, 0, 0, 8, MINIMUM_VECTOR_COMPONENT, 127, prediction);
      for (int pixel = 0; pixel < 64; pixel++) {
        final int red = prediction[pixel * 3];
        assertEquals(0, red);
        final int green = prediction[pixel * 3 + 1];
        assertEquals(127, green);
        final int blue = prediction[pixel * 3 + 2];
        assertEquals(MAXIMUM_CHANNEL_VALUE, blue);
      }
      final int[] source = new int[8 * 8 * 3];
      final int[] values = { 0, 1, 250, MAXIMUM_CHANNEL_VALUE };
      for (int pixel = 0; pixel < 64; pixel++) {
        Arrays.fill(source, pixel * 3, pixel * 3 + 3, values[pixel % values.length]);
      }
      final int[] endpoints = new int[6];
      kernels.cluster(source, 8, endpoints);
      final int[] expectedEndpoints = new int[] { 1, 1, 1, 253, 253, 253 };
      assertArrayEquals(expectedEndpoints, endpoints, level.getSymbol());
    }
  }
}
