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

  private static void failed(final Executable call) {
    assertEquals("MCV2 native kernel failed", assertThrows(IllegalStateException.class, call).getMessage());
  }

  @Test
  void checksEveryIndexBeforeTheCall() {
    final NativeKernels k = failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    k.start(block, 0, 1);
    // block sizes and the arrays that hold them
    refused(() -> k.predicted(block, 4, block));
    refused(() -> k.predicted(block, 12, block));
    refused(() -> k.predicted(block, 64, block));
    refused(() -> k.predicted(block, 8, small));
    refused(() -> k.predicted(small, 8, block));
    k.start(small, 0, 1);
    refused(() -> k.solid(0, 8, block));
    k.start(block, 0, 1);
    // records too short, or read from before their start
    refused(() -> k.palette(record, -1, 8, block));
    refused(() -> k.palette(record, 64 - 13, 8, block));
    refused(() -> k.intraGrid(record, 0, 0, 8, block));
    refused(() -> k.intraGrid(record, 0, 3, 8, block));
    refused(() -> k.intraGrid(record, 0, 16, 8, block));
    refused(() -> k.intraGrid(record, 62, 2, 8, block));
    refused(() -> k.residualGrid(small, record, 0, 2, 0, 8, block));
    refused(() -> k.residualGrid(block, record, 0, 3, 0, 8, block));
    refused(() -> k.residualGrid(block, record, 62, 2, 0, 8, block));
    refused(() -> k.reduced(null, record, 0, 3, 1, 0, 8, block));
    refused(() -> k.reduced(null, record, 0, 4, 3, 0, 8, block));
    refused(() -> k.reduced(null, record, 50, 4, 1, 0, 8, block));
    refused(() -> k.reduced(small, record, 0, 4, 1, 0, 8, block));
    refused(() -> k.compact(block, record, 0, -1, 0, 8, block));
    refused(() -> k.compact(block, record, 0, CompactRecord.LOW2 + 1, 0, 8, block));
    refused(() -> k.compact(small, record, 0, CompactRecord.DC_Y, 0, 8, block));
    refused(() -> k.compact(block, record, 60, CompactRecord.GRID4_YC, 0, 8, block));
    // pictures, and coordinates whose sums could overflow
    refused(() -> k.predict(picture, 0, 16, 0, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 4097, 16, 0, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 16, 0, 0, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 16, 4097, 0, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 17, 16, 0, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 16, 16, 0, 0, 8, 0, 0, small));
    refused(() -> k.predict(picture, 16, 16, -(1 << 20) - 1, 0, 8, 0, 0, block));
    refused(() -> k.predict(picture, 16, 16, 0, (1 << 20) + 1, 8, 0, 0, block));
    refused(() -> k.predict(picture, 16, 16, 0, 0, 8, 1 << 21, 0, block));
    refused(() -> k.predict(picture, 16, 16, 0, 0, 8, 0, -(1 << 21), block));
    // fits: strides, and outputs too short
    refused(() -> k.fit(floats, 0, 0, 8, 2, floats, 0, 1));
    refused(() -> k.fit(floats, 0, 4, 8, 2, floats, 0, 1));
    refused(() -> k.fit(floats, 3, 3, 8, 2, floats, 0, 1));
    refused(() -> k.fit(floats, 0, 3, 8, 2, floats, 0, 0));
    refused(() -> k.fit(floats, 0, 3, 8, 2, new float[3], 0, 1));
    refused(() -> k.fit(floats, 0, 3, 8, 2, floats, -1, 1));
    refused(() -> k.cellSums(block, 8, new int[FastFits.CELL_SUMS - 1]));
    refused(() -> k.lumaResidual(block, small, 8, new float[16]));
    refused(() -> k.lumaResidual(block, block, 8, new float[15]));
    refused(() -> k.cluster(block, 8, new float[5]));
    refused(() -> k.paletteCluster(block, 0, new float[6]));
    refused(() -> k.paletteCluster(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[6]));
    refused(() -> k.paletteCluster(small, 64, new float[6]));
    refused(() -> k.paletteCluster(block, 64, new float[5]));
    refused(() -> k.finish(block, -1, new float[6], false, new int[6], new byte[64]));
    refused(() -> k.finish(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[6], false, new int[6], new byte[1025]));
    refused(() -> k.finish(small, 64, new float[6], false, new int[6], new byte[64]));
    refused(() -> k.finish(block, 64, new float[6], false, new int[6], new byte[63]));
    refused(() -> k.finishPattern(small, 8, new float[6], false, new int[6], new byte[64]));
    refused(() -> k.finishPattern(block, 8, new float[6], false, new int[6], new byte[63]));
    // the motion search's picture, block and range
    refused(() -> k.seeded(picture, 17, 16, block, 0, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, small, 0, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 1 << 21, 0, 8, 0, 0, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 0, 1 << 21, 8, 0, 0, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 1 << 21, 0, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 1 << 21, 4, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, -1, true, new int[0]));
    refused(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, (1 << 20) + 1, true, new int[0]));
    // a source block starts inside the picture
    refused(() -> k.loadSource(picture, 16, 16, -1, 0, 8, block));
    refused(() -> k.loadSource(picture, 16, 16, 16, 0, 8, block));
    refused(() -> k.loadSource(picture, 16, 16, 0, -1, 8, block));
    refused(() -> k.loadSource(picture, 16, 16, 0, 16, 8, block));
    refused(() -> k.loadSource(picture, 16, 16, 0, 0, 8, small));
    refused(() -> k.loadSource(new byte[3], 16, 16, 0, 0, 8, block));
    refused(() -> k.halve(block, 0, block));
    refused(() -> k.halve(block, 7, block));
    refused(() -> k.halve(new int[64 * 64 * 3], 64, block));
    refused(() -> k.halve(small, 8, block));
    refused(() -> k.halve(block, 8, new int[4 * 4 * 3 - 1]));
    refused(() -> k.ycocg(block, -1, true, floats));
    refused(() -> k.ycocg(new int[32 * 32 * 3 + 3], 32 * 32 + 1, true, new float[32 * 32 * 3 + 3]));
    refused(() -> k.ycocg(small, 64, true, floats));
    refused(() -> k.ycocg(block, 64, true, new float[10]));
    refused(() -> k.residualTarget(floats, block, -1, true, floats));
    refused(() -> k.residualTarget(new float[32 * 32 * 3 + 3], new int[32 * 32 * 3 + 3], 32 * 32 + 1, true, new float[32 * 32 * 3 + 3]));
    refused(() -> k.residualTarget(new float[10], block, 64, true, floats));
    refused(() -> k.residualTarget(floats, small, 64, true, floats));
    refused(() -> k.residualTarget(floats, block, 64, true, new float[10]));
    refused(() -> k.cellMeans(floats, 8, -1, 2, floats, 0, 1));
    refused(() -> k.cellMeans(floats, 8, 3, 2, floats, 0, 1));
    refused(() -> k.cellMeans(floats, 8, 0, 3, floats, 0, 1));
    refused(() -> k.cellMeans(new float[10], 8, 0, 2, floats, 0, 1));
    refused(() -> k.cellMeans(floats, 8, 0, 2, floats, 0, 0));
    refused(() -> k.cellMeans(floats, 8, 0, 2, new float[3], 0, 1));
  }

  @Test
  void refusesEachBadArgumentInTheKernelThatTakesIt() {
    // every other argument is valid, so a kernel that skipped the check would reach the failing library instead
    final NativeKernels k = failing();
    final int[] big = new int[32 * 32 * 3];
    final int[] block = new int[8 * 8 * 3];
    final int[] small = new int[8 * 8 * 3 - 1];
    final byte[] record = new byte[4096];
    final float[] floats = new float[32 * 32 * 3];
    final float[] shortFloats = new float[8 * 8 * 3 - 1];
    final byte[] picture = new byte[16 * 16 * 3];
    k.start(big, 0, 1);
    // a size that is no block size
    refused(() -> k.solid(0, 12, big));
    refused(() -> k.palette(record, 0, 12, big));
    refused(() -> k.intraGrid(record, 0, 2, 12, big));
    refused(() -> k.residualGrid(big, record, 0, 2, 0, 12, big));
    refused(() -> k.reduced(null, record, 0, 4, 1, 0, 12, big));
    refused(() -> k.compact(big, record, 0, CompactRecord.DC_Y, 0, 12, big));
    refused(() -> k.predict(picture, 16, 16, 0, 0, 12, 0, 0, big));
    refused(() -> k.fit(floats, 0, 3, 12, 2, floats, 0, 1));
    refused(() -> k.cellSums(big, 12, new int[FastFits.CELL_SUMS]));
    refused(() -> k.lumaResidual(big, big, 12, new float[16]));
    refused(() -> k.cluster(big, 12, new float[6]));
    refused(() -> k.finishPattern(big, 12, new float[6], false, new int[6], new byte[1024]));
    refused(() -> k.seeded(picture, 16, 16, big, 0, 0, 12, 0, 0, 4, true, new int[0]));
    refused(() -> k.cellMeans(floats, 12, 0, 2, floats, 0, 1));
    // a grid that is no grid width, and sources one value short
    refused(() -> k.fit(floats, 0, 3, 8, 3, floats, 0, 1));
    refused(() -> k.cellSums(small, 8, new int[FastFits.CELL_SUMS]));
    refused(() -> k.lumaResidual(small, big, 8, new float[16]));
    refused(() -> k.cluster(small, 8, new float[6]));
    // records and outputs one value short of what a grid, a stride or a pixel count needs
    refused(() -> k.intraGrid(new byte[2 * 2 * 3 - 1], 0, 2, 8, big));
    refused(() -> k.residualGrid(big, new byte[2 * 2 * 3 - 1], 0, 2, 0, 8, big));
    refused(() -> k.reduced(null, new byte[4 * 4 + 2 * 2 * 2 - 1], 0, 4, 2, 0, 8, big));
    refused(() -> k.fit(floats, 0, 3, 8, 2, new float[(2 * 2 - 1) * 2], 0, 2));
    refused(() -> k.cellMeans(floats, 8, 0, 2, new float[(2 * 2 - 1) * 2], 0, 2));
    refused(() -> k.ycocg(block, 64, true, shortFloats));
    refused(() -> k.residualTarget(shortFloats, block, 64, true, floats));
    refused(() -> k.residualTarget(floats, block, 64, true, shortFloats));
  }

  @Test
  void passesEveryLimitItselfToTheLibrary() {
    // a comparison one step too strict would refuse the limit instead of reaching the failing library
    final NativeKernels k = failing();
    final int[] block = new int[8 * 8 * 3];
    final int[] big = new int[32 * 32 * 3];
    final float[] floats = new float[32 * 32 * 3];
    final byte[] line = new byte[4096 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    k.start(block, 0, 1);
    failed(() -> k.intraGrid(new byte[8 * 8 * 3], 0, Mcv2Format.MAX_GRID, 8, block));
    failed(() -> k.predict(line, 1, 4096, 0, 0, 8, 0, 0, block));
    failed(() -> k.predict(line, 4096, 1, 0, 0, 8, 0, 0, block));
    failed(() -> k.predict(picture, 16, 16, -(1 << 20), 1 << 20, 8, -(1 << 20), 1 << 20, block));
    failed(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 0, true, new int[0]));
    failed(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 1 << 20, true, new int[0]));
    failed(() -> k.finish(big, 0, new float[6], false, new int[6], new byte[0]));
    failed(() -> k.finish(big, 32 * 32, new float[6], false, new int[6], new byte[32 * 32]));
    failed(() -> k.halve(new int[2 * 2 * 3], 2, new int[3]));
    failed(() -> k.halve(big, 32, new int[16 * 16 * 3]));
    failed(() -> k.ycocg(big, 0, true, floats));
    failed(() -> k.ycocg(big, 32 * 32, true, floats));
    failed(() -> k.residualTarget(floats, big, 0, true, floats));
    failed(() -> k.residualTarget(floats, big, 32 * 32, true, floats));
  }

  @Test
  void reportsAKernelThatFails() {
    final NativeKernels k = failing();
    final int[] block = new int[8 * 8 * 3];
    final byte[] record = new byte[64];
    final float[] floats = new float[8 * 8 * 3];
    final byte[] picture = new byte[16 * 16 * 3];
    k.start(block, 0, 1);
    failed(() -> k.predicted(block, 8, block));
    failed(() -> k.solid(0, 8, block));
    failed(() -> k.palette(record, 0, 8, block));
    failed(() -> k.intraGrid(record, 0, 2, 8, block));
    failed(() -> k.residualGrid(block, record, 0, 2, 0, 8, block));
    failed(() -> k.reduced(null, record, 0, 4, 1, 0, 8, block));
    failed(() -> k.reduced(block, record, 0, 4, 1, 0, 8, block));
    failed(() -> k.compact(block, record, 0, CompactRecord.GRID4_YC, 0, 8, block));
    failed(() -> k.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    failed(() -> k.fit(floats, 0, 3, 8, 2, floats, 0, 1));
    failed(() -> k.cellSums(block, 8, new int[FastFits.CELL_SUMS]));
    failed(() -> k.lumaResidual(block, block, 8, new float[16]));
    failed(() -> k.cluster(block, 8, new float[6]));
    failed(() -> k.paletteCluster(block, 64, new float[6]));
    failed(() -> k.finish(block, 64, new float[6], false, new int[6], new byte[64]));
    failed(() -> k.finishPattern(block, 8, new float[6], false, new int[6], new byte[64]));
    failed(() -> k.seeded(picture, 16, 16, block, 0, 0, 8, 0, 0, 4, true, new int[0]));
    failed(() -> k.loadSource(picture, 16, 16, 0, 0, 8, block));
    failed(() -> k.halve(block, 8, new int[4 * 4 * 3]));
    failed(() -> k.ycocg(block, 64, true, floats));
    failed(() -> k.residualTarget(floats, block, 64, true, floats));
    failed(() -> k.cellMeans(floats, 8, 0, 2, floats, 0, 1));
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
    final NativeKernels k = failing();
    final JavaKernels java = new JavaKernels();
    final Random random = new Random(5);
    for (final int kind : new int[] { CompactRecord.VQ64, CompactRecord.PQ64, CompactRecord.GAIN_BIAS }) {
      for (int trial = 0; trial < 8; trial++) {
        final int[] source = new int[16 * 16 * 3];
        final int[] prediction = new int[source.length];
        for (int i = 0; i < source.length; i++) {
          source[i] = random.nextInt(256);
          prediction[i] = random.nextInt(1021);
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
        k.start(source, 100, limit);
        final boolean finished = java.compact(prediction, record, 1, kind, trial % 5, 16, expected);
        assertEquals(finished, k.compact(prediction, record, 1, kind, trial % 5, 16, actual));
        assertArrayEquals(expected, actual);
        if (finished) {
          assertEquals(java.distortion(), k.distortion());
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
