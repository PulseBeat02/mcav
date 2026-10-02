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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

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
import java.util.Random;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import me.brandonli.mcav.bukkit.media.mcv2.ResidualBooks;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The Java side of the native kernels: every size, offset and length a kernel would index with is checked before the
 * call, whatever the library, so a bad one fails in Java and never reaches native code; a kernel that fails is reported
 * as such; a scored kernel keeps the distortion of a block it finished and nothing of one it stopped; the compact
 * classes the library does not reconstruct fall back to Java; and every kernel, at every level this processor runs,
 * computes what Java computes.
 */
final class NativeKernelsTest {

  /** Kernels whose every call fails, so a check that let a bad argument through shows as the wrong exception. */
  private static NativeKernels failing() {
    return new NativeKernels(new NativeKernels.Binding(NativeKernels.Level.SCALAR, (name, descriptor) -> returning(descriptor, null)));
  }

  /** A handle of the descriptor's type that returns a value, or throws when the value is null. */
  private static MethodHandle returning(final FunctionDescriptor descriptor, final @Nullable Object value) {
    final MethodType type = descriptor.toMethodType();
    final MethodHandle body;
    if (value == null) {
      body = MethodHandles.insertArguments(
        MethodHandles.throwException(type.returnType(), IllegalStateException.class),
        0,
        new IllegalStateException("the library failed")
      );
    } else {
      body = MethodHandles.constant(type.returnType(), value);
    }
    return MethodHandles.dropArguments(body, 0, type.parameterList());
  }

  private static void refused(final Executable call) {
    assertThrows(IllegalArgumentException.class, call);
  }

  @Test
  void refusesAnOutputStrideWhoseSpanAnIntCannotHold() {
    final NativeKernels kernels = failing();
    final float[] values = new float[8 * 8 * 3];
    final float[] out = new float[1];
    // 15 strides of 2^30 span 15 * 2^30 + 1 values, which an int wraps around to -2^30 + 1; 15 of 0x11111111 wrap to 0
    for (final int stride : new int[] { 1 << 30, 0x11111111 }) {
      refused(() -> kernels.fit(values, 0, 3, 8, 4, out, 0, stride));
      refused(() -> kernels.cellMeans(values, 8, 0, 4, out, 0, stride));
    }
  }

  private static void failed(final Executable call) {
    assertEquals("MCV2 native kernel failed", assertThrows(IllegalStateException.class, call).getMessage());
  }

  @Test
  void forgetsTheArraysItWasPassed() {
    final NativeKernels kernels = failing();
    final byte[] picture = new byte[16 * 16 * 3];
    final int[] block = new int[8 * 8 * 3];
    failed(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    assertTrue(kernels.keeps(picture), "a coder passes the same picture block after block");
    kernels.forgetArrays();
    assertFalse(kernels.keeps(picture));
    assertFalse(kernels.keeps(block));
  }

  @Test
  void checksEveryIndexBeforeTheCall() {
    final NativeKernels kernels = failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    // block sizes and the arrays that hold them
    refused(() -> kernels.predicted(block, 4, block));
    refused(() -> kernels.predicted(block, 12, block));
    refused(() -> kernels.predicted(block, 64, block));
    refused(() -> kernels.predicted(block, 8, small));
    refused(() -> kernels.predicted(small, 8, block));
    kernels.start(small, 0, 1);
    refused(() -> kernels.solid(0, 8, block));
    kernels.start(block, 0, 1);
    // records too short, or read from before their start
    refused(() -> kernels.palette(record, -1, 8, block));
    refused(() -> kernels.palette(record, 64 - 13, 8, block));
    refused(() -> kernels.intraGrid(record, 0, 0, 8, block));
    refused(() -> kernels.intraGrid(record, 0, 3, 8, block));
    refused(() -> kernels.intraGrid(record, 0, 16, 8, block));
    refused(() -> kernels.intraGrid(record, 62, 2, 8, block));
    refused(() -> kernels.residualGrid(small, record, 0, 2, 0, 8, block));
    refused(() -> kernels.residualGrid(block, record, 0, 3, 0, 8, block));
    refused(() -> kernels.residualGrid(block, record, 62, 2, 0, 8, block));
    refused(() -> kernels.reduced(null, record, 0, 3, 1, 0, 8, block));
    refused(() -> kernels.reduced(null, record, 0, 4, 3, 0, 8, block));
    refused(() -> kernels.reduced(null, record, 50, 4, 1, 0, 8, block));
    refused(() -> kernels.reduced(small, record, 0, 4, 1, 0, 8, block));
    refused(() -> kernels.compact(block, record, 0, -1, 0, 8, block));
    refused(() -> kernels.compact(block, record, 0, CompactRecord.LOW2 + 1, 0, 8, block));
    refused(() -> kernels.compact(small, record, 0, CompactRecord.DC_Y, 0, 8, block));
    refused(() -> kernels.compact(block, record, 60, CompactRecord.GRID4_YC, 0, 8, block));
    // pictures, and coordinates whose sums could overflow
    refused(() -> kernels.predict(picture, 0, 16, 0, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 4097, 16, 0, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 16, 0, 0, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 16, 4097, 0, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 17, 16, 0, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, small));
    refused(() -> kernels.predict(picture, 16, 16, -(1 << 20) - 1, 0, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 16, 16, 0, (1 << 20) + 1, 8, 0, 0, block));
    refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 1 << 21, 0, block));
    refused(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, -(1 << 21), block));
    // fits: strides, and outputs too short
    refused(() -> kernels.fit(floats, 0, 0, 8, 2, floats, 0, 1));
    refused(() -> kernels.fit(floats, 0, 4, 8, 2, floats, 0, 1));
    refused(() -> kernels.fit(floats, 3, 3, 8, 2, floats, 0, 1));
    refused(() -> kernels.fit(floats, 0, 3, 8, 2, floats, 0, 0));
    refused(() -> kernels.fit(floats, 0, 3, 8, 2, new float[3], 0, 1));
    refused(() -> kernels.fit(floats, 0, 3, 8, 2, floats, -1, 1));
    refused(() -> kernels.cellSums(block, 8, new int[FastFits.CELL_SUMS - 1]));
    refused(() -> kernels.lumaResidual(block, small, 8, new float[16]));
    refused(() -> kernels.lumaResidual(block, block, 8, new float[15]));
    refused(() -> kernels.cluster(block, 8, new float[5]));
    refused(() -> kernels.paletteCluster(block, 0, new float[6]));
    refused(() -> kernels.paletteCluster(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[6]));
    refused(() -> kernels.paletteCluster(small, 64, new float[6]));
    refused(() -> kernels.paletteCluster(block, 64, new float[5]));
    refused(() -> kernels.finish(block, -1, new float[6], false, new int[6], new byte[64]));
    refused(() -> kernels.finish(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[6], false, new int[6], new byte[1025]));
    refused(() -> kernels.finish(small, 64, new float[6], false, new int[6], new byte[64]));
    refused(() -> kernels.finish(block, 64, new float[6], false, new int[6], new byte[63]));
    refused(() -> kernels.finishPattern(small, 8, new float[6], false, new int[6], new byte[64]));
    refused(() -> kernels.finishPattern(block, 8, new float[6], false, new int[6], new byte[63]));
    // the motion search's picture, block and range
    refused(() -> kernels.seeded(picture, 17, 16, block, 0, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, small, 0, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 1 << 21, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 1 << 21, 8, 0, 0, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 1 << 21, 0, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 1 << 21, 4, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, -1, true, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, (1 << 20) + 1, true, new int[0]));
    // a source block starts inside the picture
    refused(() -> kernels.loadSource(picture, 16, 16, -1, 0, 8, block));
    refused(() -> kernels.loadSource(picture, 16, 16, 16, 0, 8, block));
    refused(() -> kernels.loadSource(picture, 16, 16, 0, -1, 8, block));
    refused(() -> kernels.loadSource(picture, 16, 16, 0, 16, 8, block));
    refused(() -> kernels.loadSource(picture, 16, 16, 0, 0, 8, small));
    refused(() -> kernels.loadSource(new byte[3], 16, 16, 0, 0, 8, block));
    refused(() -> kernels.halve(block, 0, block));
    refused(() -> kernels.halve(block, 7, block));
    refused(() -> kernels.halve(new int[64 * 64 * 3], 64, block));
    refused(() -> kernels.halve(small, 8, block));
    refused(() -> kernels.halve(block, 8, new int[4 * 4 * 3 - 1]));
    refused(() -> kernels.ycocg(block, -1, true, floats));
    refused(() -> kernels.ycocg(new int[32 * 32 * 3 + 3], 32 * 32 + 1, true, new float[32 * 32 * 3 + 3]));
    refused(() -> kernels.ycocg(small, 64, true, floats));
    refused(() -> kernels.ycocg(block, 64, true, new float[10]));
    refused(() -> kernels.residualTarget(floats, block, -1, true, floats));
    refused(() ->
      kernels.residualTarget(new float[32 * 32 * 3 + 3], new int[32 * 32 * 3 + 3], 32 * 32 + 1, true, new float[32 * 32 * 3 + 3])
    );
    refused(() -> kernels.residualTarget(new float[10], block, 64, true, floats));
    refused(() -> kernels.residualTarget(floats, small, 64, true, floats));
    refused(() -> kernels.residualTarget(floats, block, 64, true, new float[10]));
    refused(() -> kernels.cellMeans(floats, 8, -1, 2, floats, 0, 1));
    refused(() -> kernels.cellMeans(floats, 8, 3, 2, floats, 0, 1));
    refused(() -> kernels.cellMeans(floats, 8, 0, 3, floats, 0, 1));
    refused(() -> kernels.cellMeans(new float[10], 8, 0, 2, floats, 0, 1));
    refused(() -> kernels.cellMeans(floats, 8, 0, 2, floats, 0, 0));
    refused(() -> kernels.cellMeans(floats, 8, 0, 2, new float[3], 0, 1));
  }

  @Test
  void refusesEachBadArgumentInTheKernelThatTakesIt() {
    // every other argument is valid, so a kernel that skipped the check would reach the failing library instead
    final NativeKernels kernels = failing();
    final int[] big = new int[32 * 32 * 3];
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[4096];
    final float[] floats = new float[32 * 32 * 3];
    final float[] shortFloats = new float[8 * 8 * 3 - 1];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(big, 0, 1);
    // a size that is no block size
    refused(() -> kernels.solid(0, 12, big));
    refused(() -> kernels.palette(record, 0, 12, big));
    refused(() -> kernels.intraGrid(record, 0, 2, 12, big));
    refused(() -> kernels.residualGrid(big, record, 0, 2, 0, 12, big));
    refused(() -> kernels.reduced(null, record, 0, 4, 1, 0, 12, big));
    refused(() -> kernels.compact(big, record, 0, CompactRecord.DC_Y, 0, 12, big));
    refused(() -> kernels.predict(picture, 16, 16, 0, 0, 12, 0, 0, big));
    refused(() -> kernels.fit(floats, 0, 3, 12, 2, floats, 0, 1));
    refused(() -> kernels.cellSums(big, 12, new int[FastFits.CELL_SUMS]));
    refused(() -> kernels.lumaResidual(big, big, 12, new float[16]));
    refused(() -> kernels.cluster(big, 12, new float[6]));
    refused(() -> kernels.finishPattern(big, 12, new float[6], false, new int[6], new byte[1024]));
    refused(() -> kernels.seeded(picture, 16, 16, big, 0, 0, 12, 0, 0, 4, true, new int[0]));
    refused(() -> kernels.loadSource(picture, 16, 16, 0, 0, 12, big));
    refused(() -> kernels.cellMeans(floats, 12, 0, 2, floats, 0, 1));
    // a grid that is no grid width, and sources one value short
    refused(() -> kernels.fit(floats, 0, 3, 8, 3, floats, 0, 1));
    refused(() -> kernels.cellSums(small, 8, new int[FastFits.CELL_SUMS]));
    refused(() -> kernels.lumaResidual(small, big, 8, new float[16]));
    refused(() -> kernels.cluster(small, 8, new float[6]));
    // records and outputs one value short of what a grid, a stride or a pixel count needs
    refused(() -> kernels.intraGrid(new byte[2 * 2 * 3 - 1], 0, 2, 8, big));
    refused(() -> kernels.residualGrid(big, new byte[2 * 2 * 3 - 1], 0, 2, 0, 8, big));
    refused(() -> kernels.reduced(null, new byte[4 * 4 + 2 * 2 * 2 - 1], 0, 4, 2, 0, 8, big));
    refused(() -> kernels.fit(floats, 0, 3, 8, 2, new float[(2 * 2 - 1) * 2], 0, 2));
    refused(() -> kernels.cellMeans(floats, 8, 0, 2, new float[(2 * 2 - 1) * 2], 0, 2));
    refused(() -> kernels.ycocg(block, 64, true, shortFloats));
    refused(() -> kernels.residualTarget(shortFloats, block, 64, true, floats));
    refused(() -> kernels.residualTarget(floats, block, 64, true, shortFloats));
  }

  @Test
  void passesEveryLimitItselfToTheLibrary() {
    // a comparison one step too strict would refuse the limit instead of reaching the failing library
    final NativeKernels kernels = failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] big = new int[32 * 32 * 3];
    final float[] floats = new float[32 * 32 * 3];
    final byte[] line = new byte[4096 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    failed(() -> kernels.intraGrid(new byte[8 * 8 * 3], 0, Mcv2Format.MAX_GRID, 8, block));
    failed(() -> kernels.predict(line, 1, 4096, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.predict(line, 4096, 1, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.predict(picture, 16, 16, -(1 << 20), 1 << 20, 8, -(1 << 20), 1 << 20, block));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 0, true, new int[0]));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 1 << 20, true, new int[0]));
    failed(() -> kernels.finish(big, 0, new float[6], false, new int[6], new byte[0]));
    failed(() -> kernels.finish(big, 32 * 32, new float[6], false, new int[6], new byte[32 * 32]));
    failed(() -> kernels.halve(new int[2 * 2 * 3], 2, new int[3]));
    failed(() -> kernels.halve(big, 32, new int[16 * 16 * 3]));
    failed(() -> kernels.ycocg(big, 0, true, floats));
    failed(() -> kernels.ycocg(big, 32 * 32, true, floats));
    failed(() -> kernels.residualTarget(floats, big, 0, true, floats));
    failed(() -> kernels.residualTarget(floats, big, 32 * 32, true, floats));
  }

  @Test
  void reportsAKernelThatFails() {
    final NativeKernels kernels = failing();
    final int[] block = new int[8 * 8 * 3];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    kernels.start(block, 0, 1);
    failed(() -> kernels.predicted(block, 8, block));
    failed(() -> kernels.solid(0, 8, block));
    failed(() -> kernels.palette(record, 0, 8, block));
    failed(() -> kernels.intraGrid(record, 0, 2, 8, block));
    failed(() -> kernels.residualGrid(block, record, 0, 2, 0, 8, block));
    failed(() -> kernels.reduced(null, record, 0, 4, 1, 0, 8, block));
    failed(() -> kernels.reduced(block, record, 0, 4, 1, 0, 8, block));
    failed(() -> kernels.compact(block, record, 0, CompactRecord.GRID4_YC, 0, 8, block));
    failed(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.fit(floats, 0, 3, 8, 2, floats, 0, 1));
    failed(() -> kernels.cellSums(block, 8, new int[FastFits.CELL_SUMS]));
    failed(() -> kernels.lumaResidual(block, block, 8, new float[16]));
    failed(() -> kernels.cluster(block, 8, new float[6]));
    failed(() -> kernels.paletteCluster(block, 64, new float[6]));
    failed(() -> kernels.finish(block, 64, new float[6], false, new int[6], new byte[64]));
    failed(() -> kernels.finishPattern(block, 8, new float[6], false, new int[6], new byte[64]));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 4, true, new int[0]));
    failed(() -> kernels.loadSource(picture, 16, 16, 0, 0, 8, block));
    failed(() -> kernels.halve(block, 8, new int[4 * 4 * 3]));
    failed(() -> kernels.ycocg(block, 64, true, floats));
    failed(() -> kernels.residualTarget(floats, block, 64, true, floats));
    failed(() -> kernels.cellMeans(floats, 8, 0, 2, floats, 0, 1));
  }

  @Test
  void keepsTheDistortionOfAFinishedBlockOnly() {
    final NativeKernels stopped = new NativeKernels(
      new NativeKernels.Binding(NativeKernels.Level.SCALAR, (name, descriptor) ->
        returning(descriptor, name.equals("predicted") ? -1L : null)
      )
    );
    final NativeKernels finished = new NativeKernels(
      new NativeKernels.Binding(NativeKernels.Level.SCALAR, (name, descriptor) ->
        returning(descriptor, name.equals("predicted") ? 42L : null)
      )
    );
    final int[] block = new int[8 * 8 * 3];
    finished.start(block, 0, 1);
    assertTrue(finished.predicted(block, 8, block));
    assertEquals(42, finished.distortion());
    stopped.start(block, 0, 1);
    assertFalse(stopped.predicted(block, 8, block));
    assertEquals(0, stopped.distortion());
    assertEquals(NativeKernels.Level.SCALAR, finished.level());
    assertEquals("avx2", NativeKernels.Level.AVX2.symbol());
  }

  @Test
  void reconstructsTheOtherCompactClassesInJava() {
    // the classes the encoder never tries: a failing library is never asked for them
    final NativeKernels kernels = failing();
    final JavaKernels java = new JavaKernels();
    final Random random = new Random(5);
    for (final int kind : new int[] { CompactRecord.VQ64, CompactRecord.PQ64, CompactRecord.GAIN_BIAS }) {
      for (int trial = 0; trial < 8; trial++) {
        final int[] source = new int[16 * 16 * 3];
        final int[] prediction = new int[source.length];
        for (int index = 0; index < source.length; index++) {
          source[index] = random.nextInt(256);
          prediction[index] = random.nextInt(1021);
        }
        final byte[] record = new byte[CompactRecord.bodyBytes(kind) + 1];
        random.nextBytes(record);
        // valid book indices, as the parser would have checked: one below 64, or two in twelve bits
        if (kind == CompactRecord.VQ64) {
          record[1 + 3] = (byte) random.nextInt(ResidualBooks.VECTORS);
        } else if (kind == CompactRecord.PQ64) {
          record[1 + 4] = (byte) random.nextInt(16);
        }
        final double limit = trial % 2 == 0 ? Double.POSITIVE_INFINITY : 30_000;
        final int[] expected = new int[source.length];
        final int[] actual = new int[source.length];
        java.start(source, 100, limit);
        kernels.start(source, 100, limit);
        final boolean finished = java.compact(prediction, record, 1, kind, trial % 5, 16, expected);
        assertEquals(finished, kernels.compact(prediction, record, 1, kind, trial % 5, 16, actual));
        assertArrayEquals(expected, actual);
        if (finished) {
          assertEquals(java.distortion(), kernels.distortion());
        }
      }
    }
  }

  @Test
  void computesEveryKernelAsJavaDoesAtEveryLevel() {
    for (final NativeKernels.Level level : NativeTesting.levels()) {
      final NativeKernels kernels = NativeTesting.kernels(level);
      for (int kernel = 0; kernel < KernelDifferential.KERNELS; kernel++) {
        for (int seed = 0; seed < 12; seed++) {
          final KernelDifferential.Values values = KernelDifferential.of(new Random(seed * 31L + kernel));
          assertNull(KernelDifferential.compare(values, new JavaKernels(), kernels, kernel), level + " kernel " + kernel + " seed " + seed);
        }
      }
    }
  }
}
