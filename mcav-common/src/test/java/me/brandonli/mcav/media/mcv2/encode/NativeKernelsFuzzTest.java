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

import static org.junit.jupiter.api.Assertions.assertNull;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the native kernels against the Java ones: the fuzzed bytes choose a level this processor runs, a kernel and all
 * its inputs, and the native kernel must compute exactly what the Java one computes. It runs on the release library the
 * jar ships; where no library loads there is nothing to compare, and every input passes. The seeds are random byte
 * strings, which the fuzzer mutates into every kernel and size.
 */
@Tag("fuzz")
final class NativeKernelsFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void computesWhatJavaComputes(final FuzzedDataProvider data) {
    final List<NativeKernels.Level> levels = NativeTesting.levels();
    if (levels.isEmpty()) {
      return;
    }
    final NativeKernels.Level level = levels.get(data.consumeInt(0, levels.size() - 1));
    final KernelDifferential.Values values = new KernelDifferential.Values() {
      @Override
      public int next(final int low, final int high) {
        return data.consumeInt(low, high);
      }

      @Override
      public byte[] bytes(final int count) {
        return Arrays.copyOf(data.consumeBytes(count), count);
      }
    };
    assertNull(KernelDifferential.compare(values, new JavaKernels(), NativeTesting.kernels(level)), level.symbol());
  }
}
