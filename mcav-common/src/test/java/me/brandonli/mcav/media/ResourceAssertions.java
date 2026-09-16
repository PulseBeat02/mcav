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
package me.brandonli.mcav.media;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * Assertions for factories that return a resource, such as an image buffer or a frame grabber, and that should throw
 * instead. A resource the factory returns by mistake is closed before the test fails, so no native memory leaks.
 */
public final class ResourceAssertions {

  private ResourceAssertions() {
    throw new UnsupportedOperationException("utility class");
  }

  /**
   * Asserts that opening a resource throws the expected exception.
   *
   * @param expected the type of exception the factory should throw
   * @param opening  the factory that opens the resource
   * @param <T>      the type of exception
   * @return the exception the factory threw
   */
  public static <T extends Throwable> T assertThrowsWhileOpening(
    final Class<T> expected,
    final ThrowingSupplier<? extends AutoCloseable> opening
  ) {
    final Executable openAndClose = () -> {
      try (final AutoCloseable opened = opening.get()) {
        fail("expected " + expected + " but the factory opened " + opened);
      }
    };
    return assertThrows(expected, openAndClose);
  }
}
