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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Records the open SIMD input-domain finding without loading or calling a native library. */
final class NativeHalveDomainDefectTest {

  @Disabled("OPEN defect: DR-031 unsupported SIMD rows reach native dispatch")
  @Test
  void unsupportedSimdRowsAreRefusedBeforeNativeDispatch() {
    final NativeKernels kernels = new NativeKernels(
      new NativeKernels.Binding(NativeKernels.Level.AVX2, (_, descriptor) -> failingBoundary(descriptor))
    );
    assertThrows(IllegalArgumentException.class, () -> kernels.halve(new int[18 * 18 * 3], 18, new int[9 * 9 * 3]));
  }

  private static MethodHandle failingBoundary(final FunctionDescriptor descriptor) {
    final MethodType type = descriptor.toMethodType();
    final MethodHandle failure = MethodHandles.insertArguments(
      MethodHandles.throwException(type.returnType(), IllegalStateException.class),
      0,
      new IllegalStateException("unexpected simulated native dispatch")
    );
    return MethodHandles.dropArguments(failure, 0, type.parameterList());
  }
}
