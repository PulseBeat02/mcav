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
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.NativeTesting.NativeKernels;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The Java side of the native kernels: every size, offset and length a kernel would index with is checked before the
 * call, whatever the library, so a bad one fails in Java and never reaches native code; a kernel that fails is reported
 * as such; a scored kernel keeps the distortion of a block it finished and nothing of one it stopped; every kernel, at every level this processor runs,
 * computes what Java computes.
 */
final class NativeKernelsTest {

  /** Kernels whose every call fails, so a check that let a bad argument through shows as the wrong exception. */
  private static NativeKernels failing() {
    return NativeTesting.kernels(new Binding(Level.SCALAR, (name, descriptor) -> returning(descriptor, null)));
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
      refused(() -> kernels.fit(values, 0, 3, 8, out, 0, stride));
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
    refused(() -> kernels.compact(block, record, 0, -1, 0, 8, block));
    refused(() -> kernels.compact(block, record, 0, Mcv2Decoder.COMPACT_GRID_Y + 1, 0, 8, block));
    refused(() -> kernels.compact(small, record, 0, Mcv2Decoder.COMPACT_DC, 0, 8, block));
    refused(() -> kernels.compact(block, record, 60, Mcv2Decoder.COMPACT_GRID, 0, 8, block));
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
    refused(() -> kernels.fit(floats, 0, 0, 8, floats, 0, 1));
    refused(() -> kernels.fit(floats, 0, 4, 8, floats, 0, 1));
    refused(() -> kernels.fit(floats, 3, 3, 8, floats, 0, 1));
    refused(() -> kernels.fit(floats, 0, 3, 8, floats, 0, 0));
    refused(() -> kernels.fit(floats, 0, 3, 8, new float[3], 0, 1));
    refused(() -> kernels.fit(floats, 0, 3, 8, floats, -1, 1));
    refused(() -> kernels.cluster(block, 8, new float[5]));
    refused(() -> kernels.finish(block, -1, new float[6], new int[6], new byte[64]));
    refused(() -> kernels.finish(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[6], new int[6], new byte[1025]));
    refused(() -> kernels.finish(small, 64, new float[6], new int[6], new byte[64]));
    refused(() -> kernels.finish(block, 64, new float[6], new int[6], new byte[63]));
    refused(() -> kernels.finishPattern(small, 8, new float[6], new int[6], new byte[64]));
    refused(() -> kernels.finishPattern(block, 8, new float[6], new int[6], new byte[63]));
    // the motion search's picture, block and range
    refused(() -> kernels.seeded(picture, 17, 16, block, 0, 0, 8, 4, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, small, 0, 0, 8, 4, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 1 << 21, 0, 8, 4, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 1 << 21, 8, 4, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, -1, new int[0]));
    refused(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, (1 << 20) + 1, new int[0]));
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
    refused(() -> kernels.ycocg(block, -1, floats));
    refused(() -> kernels.ycocg(new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[32 * 32 * 3 + 3]));
    refused(() -> kernels.ycocg(small, 64, floats));
    refused(() -> kernels.ycocg(block, 64, new float[10]));
    refused(() -> kernels.residualTarget(floats, block, -1, floats));
    refused(() -> kernels.residualTarget(new float[32 * 32 * 3 + 3], new int[32 * 32 * 3 + 3], 32 * 32 + 1, new float[32 * 32 * 3 + 3]));
    refused(() -> kernels.residualTarget(new float[10], block, 64, floats));
    refused(() -> kernels.residualTarget(floats, small, 64, floats));
    refused(() -> kernels.residualTarget(floats, block, 64, new float[10]));
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
    refused(() -> kernels.compact(big, record, 0, Mcv2Decoder.COMPACT_DC, 0, 12, big));
    refused(() -> kernels.predict(picture, 16, 16, 0, 0, 12, 0, 0, big));
    refused(() -> kernels.fit(floats, 0, 3, 12, floats, 0, 1));
    refused(() -> kernels.cluster(big, 12, new float[6]));
    refused(() -> kernels.finishPattern(big, 12, new float[6], new int[6], new byte[1024]));
    refused(() -> kernels.seeded(picture, 16, 16, big, 0, 0, 12, 4, new int[0]));
    refused(() -> kernels.loadSource(picture, 16, 16, 0, 0, 12, big));
    // a grid that is no grid width, and sources one value short
    refused(() -> kernels.cluster(small, 8, new float[6]));
    // records and outputs one value short of what a grid, a stride or a pixel count needs
    refused(() -> kernels.fit(floats, 0, 3, 8, new float[(4 * 4 - 1) * 2], 0, 2));
    refused(() -> kernels.ycocg(block, 64, shortFloats));
    refused(() -> kernels.residualTarget(shortFloats, block, 64, floats));
    refused(() -> kernels.residualTarget(floats, block, 64, shortFloats));
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
    failed(() -> kernels.predict(line, 1, 4096, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.predict(line, 4096, 1, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.predict(picture, 16, 16, -(1 << 20), 1 << 20, 8, -(1 << 20), 1 << 20, block));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 0, new int[0]));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 1 << 20, new int[0]));
    failed(() -> kernels.finish(big, 0, new float[6], new int[6], new byte[0]));
    failed(() -> kernels.finish(big, 32 * 32, new float[6], new int[6], new byte[32 * 32]));
    failed(() -> kernels.halve(new int[2 * 2 * 3], 2, new int[3]));
    failed(() -> kernels.halve(big, 32, new int[16 * 16 * 3]));
    failed(() -> kernels.ycocg(big, 0, floats));
    failed(() -> kernels.ycocg(big, 32 * 32, floats));
    failed(() -> kernels.residualTarget(floats, big, 0, floats));
    failed(() -> kernels.residualTarget(floats, big, 32 * 32, floats));
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
    failed(() -> kernels.compact(block, record, 0, Mcv2Decoder.COMPACT_GRID, 0, 8, block));
    failed(() -> kernels.predict(picture, 16, 16, 0, 0, 8, 0, 0, block));
    failed(() -> kernels.fit(floats, 0, 3, 8, floats, 0, 1));
    failed(() -> kernels.cluster(block, 8, new float[6]));
    failed(() -> kernels.finish(block, 64, new float[6], new int[6], new byte[64]));
    failed(() -> kernels.finishPattern(block, 8, new float[6], new int[6], new byte[64]));
    failed(() -> kernels.seeded(picture, 16, 16, block, 0, 0, 8, 4, new int[0]));
    failed(() -> kernels.loadSource(picture, 16, 16, 0, 0, 8, block));
    failed(() -> kernels.halve(block, 8, new int[4 * 4 * 3]));
    failed(() -> kernels.ycocg(block, 64, floats));
    failed(() -> kernels.residualTarget(floats, block, 64, floats));
  }

  @Test
  void keepsTheDistortionOfAFinishedBlockOnly() {
    final NativeKernels stopped = NativeTesting.kernels(
      new Binding(Level.SCALAR, (name, descriptor) -> returning(descriptor, name.equals("predicted") ? -1L : null))
    );
    final NativeKernels finished = NativeTesting.kernels(
      new Binding(Level.SCALAR, (name, descriptor) -> returning(descriptor, name.equals("predicted") ? 42L : null))
    );
    final int[] block = new int[8 * 8 * 3];
    finished.start(block, 0, 1);
    assertTrue(finished.predicted(block, 8, block));
    assertEquals(42, finished.distortion());
    stopped.start(block, 0, 1);
    assertFalse(stopped.predicted(block, 8, block));
    assertEquals(0, stopped.distortion());
    assertEquals(Level.SCALAR, finished.level());
    assertEquals("avx2", Level.AVX2.symbol());
  }

  @Test
  void computesEveryKernelAsJavaDoesAtEveryLevel() {
    for (final Level level : NativeTesting.levels()) {
      final NativeKernels kernels = NativeTesting.kernels(level);
      for (int kernel = 0; kernel < KernelDifferential.KERNELS; kernel++) {
        for (int seed = 0; seed < 12; seed++) {
          final KernelDifferential.Values values = KernelDifferential.of(new Random(seed * 31L + kernel));
          assertNull(
            KernelDifferential.compare(values, Mcv2Internals.javaKernels(), kernels, kernel),
            level + " kernel " + kernel + " seed " + seed
          );
        }
      }
    }
  }

  @Test
  void checksCompactQuantizersAndExactRecordBoundaries() {
    final NativeKernels kernels = failing();
    final int[] block = new int[8 * 8 * 3];
    kernels.start(block, 0, 1);
    for (final int kind : new int[] { Mcv2Decoder.COMPACT_DC, Mcv2Decoder.COMPACT_GRID, Mcv2Decoder.COMPACT_GRID_Y }) {
      final int length = kind == Mcv2Decoder.COMPACT_DC ? 1 : kind == Mcv2Decoder.COMPACT_GRID ? 10 : 8;
      final byte[] record = new byte[2 + length];
      refused(() -> kernels.compact(block, record, 2, kind, -1, 8, block));
      refused(() -> kernels.compact(block, record, 2, kind, 8, 8, block));
      refused(() -> kernels.compact(block, record, -1, kind, 0, 8, block));
      refused(() -> kernels.compact(block, record, 3, kind, 0, 8, block));
      failed(() -> kernels.compact(block, record, 2, kind, 0, 8, block));
      failed(() -> kernels.compact(block, record, 2, kind, 7, 8, block));
    }
  }

  @Test
  void refusesShortPaletteColorArraysBeforeDispatch() {
    final NativeKernels kernels = failing();
    final int[] block = new int[8 * 8 * 3];
    refused(() -> kernels.finish(block, 64, new float[6], new int[5], new byte[64]));
    refused(() -> kernels.finishPattern(block, 8, new float[6], new int[5], new byte[64]));
  }
}
