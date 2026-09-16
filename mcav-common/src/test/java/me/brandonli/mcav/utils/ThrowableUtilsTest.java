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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ThrowableUtils}.
 */
final class ThrowableUtilsTest {

  @Test
  void rethrowsVirtualMachineErrors() {
    final StackOverflowError stackOverflow = new StackOverflowError("too deep");
    final OutOfMemoryError outOfMemory = new OutOfMemoryError("heap is full");
    final StackOverflowError thrownStackOverflow = assertThrows(StackOverflowError.class, () -> ThrowableUtils.throwIfFatal(stackOverflow));
    final OutOfMemoryError thrownOutOfMemory = assertThrows(OutOfMemoryError.class, () -> ThrowableUtils.throwIfFatal(outOfMemory));
    assertSame(stackOverflow, thrownStackOverflow, "the original error is rethrown, not a copy");
    assertSame(outOfMemory, thrownOutOfMemory, "the original error is rethrown, not a copy");
  }

  @Test
  void returnsNormallyForRecoverableFailures() {
    final IllegalStateException runtimeFailure = new IllegalStateException("filter bug");
    final AssertionError assertionFailure = new AssertionError("filter assertion");
    final UnsatisfiedLinkError linkFailure = new UnsatisfiedLinkError("no jniopencv_core");
    final IOException checkedFailure = new IOException("offline");
    assertDoesNotThrow(() -> ThrowableUtils.throwIfFatal(runtimeFailure));
    assertDoesNotThrow(() -> ThrowableUtils.throwIfFatal(assertionFailure));
    assertDoesNotThrow(() -> ThrowableUtils.throwIfFatal(linkFailure));
    assertDoesNotThrow(() -> ThrowableUtils.throwIfFatal(checkedFailure));
  }

  @Test
  void rejectsNull() {
    assertThrows(NullPointerException.class, () -> ThrowableUtils.throwIfFatal(null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ThrowableUtils.class);
  }
}
