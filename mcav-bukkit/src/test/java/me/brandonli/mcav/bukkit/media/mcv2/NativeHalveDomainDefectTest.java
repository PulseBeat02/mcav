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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.NativeTesting.NativeKernels;
import org.junit.jupiter.api.Test;

/**
 * The vector loops of the native halve kernel need power-of-two blocks, so Java refuses every other size before any
 * native call; checked on a simulated library that fails every call.
 */
final class NativeHalveDomainDefectTest {

  @Test
  void unsupportedSimdRowsAreRefusedBeforeNativeDispatch() {
    final NativeKernels kernels = NativeTesting.kernels(new Binding(Level.AVX2, (_, descriptor) -> failingBoundary(descriptor)));
    assertThrows(IllegalArgumentException.class, () -> kernels.halve(new int[18 * 18 * 3], 18, new int[9 * 9 * 3]));
    // every even size that is no power of two leaves a part of a row to a vector loop of some level
    for (int size = 6; size < 32; size += 2) {
      if (Integer.bitCount(size) != 1) {
        final int refused = size;
        assertThrows(
          IllegalArgumentException.class,
          () -> kernels.halve(new int[refused * refused * 3], refused, new int[(refused / 2) * (refused / 2) * 3]),
          () -> "size " + refused
        );
      }
    }
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
