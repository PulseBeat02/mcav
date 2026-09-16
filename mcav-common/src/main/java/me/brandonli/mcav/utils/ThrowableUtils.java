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
package me.brandonli.mcav.utils;

import com.google.common.base.Preconditions;

/**
 * Helpers for code that catches failures at a thread boundary, such as a render thread that runs user filters, and
 * must keep running after a failure without hiding the failures that no code can recover from.
 */
public final class ThrowableUtils {

  private ThrowableUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Rethrows the failure if the virtual machine cannot recover from it, and returns normally otherwise.
   *
   * <p>A {@link VirtualMachineError}, such as an {@link OutOfMemoryError} or a {@link StackOverflowError}, means the
   * virtual machine is broken or out of resources, so reporting it and carrying on would hide it. Call this first in
   * a {@code catch (RuntimeException | Error)} block and handle every other failure as usual.
   *
   * @param failure the caught failure
   * @throws VirtualMachineError  the failure itself, if it is a {@link VirtualMachineError}
   * @throws NullPointerException if the failure is null
   */
  public static void throwIfFatal(final Throwable failure) {
    Preconditions.checkNotNull(failure, "Failure must not be null");
    if (failure instanceof final VirtualMachineError fatal) {
      throw fatal;
    }
  }
}
