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

import static org.junit.jupiter.api.Assertions.assertNull;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Tag;

// where no library loads, every input passes
@Tag("fuzz")
final class NativeKernelsFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldComputeWhatJavaComputes(final FuzzedDataProvider data) {
    final List<Level> levels = NativeTesting.levels();
    if (levels.isEmpty()) {
      return;
    }

    final Level level = levels.get(data.consumeInt(0, levels.size() - 1));
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
    final @Nullable String difference = KernelDifferential.compare(values, Mcv2Internals.javaKernels(), NativeTesting.kernels(level));
    assertNull(difference, level.getSymbol());
  }
}
