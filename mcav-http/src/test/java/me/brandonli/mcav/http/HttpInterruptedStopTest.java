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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/** Tests cleanup when a plugin interrupts the thread that starts its audio server. */
final class HttpInterruptedStopTest {

  @Test
  void closesAnActiveSpringContextFromAnInterruptedStartupThread() {
    try (final GenericApplicationContext context = new GenericApplicationContext()) {
      context.refresh();
      final Thread current = Thread.currentThread();
      current.interrupt();
      try {
        HttpResultImpl.closeWithOwnClassLoader(context);

        assertTrue(current.isInterrupted(), "cleanup preserves the caller's interrupt");
        assertFalse(context.isActive(), "the interrupted startup thread must still close its Spring context");
      } finally {
        final boolean _ = Thread.interrupted();
      }
    }
  }

  @Test
  void restoresTheInterruptWhenContextCleanupThrows() {
    final ConfigurableApplicationContext context = Mockito.mock(ConfigurableApplicationContext.class);
    final IllegalStateException failure = new IllegalStateException("context close failed");
    final Thread current = Thread.currentThread();
    Mockito.doAnswer(_ -> {
      assertFalse(current.isInterrupted(), "Spring cleanup must be able to acquire its shutdown lock");
      throw failure;
    })
      .when(context)
      .close();

    current.interrupt();
    try {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> HttpResultImpl.closeWithOwnClassLoader(context));
      assertSame(failure, thrown);
      assertTrue(current.isInterrupted(), "failed cleanup also preserves the caller's interrupt");
    } finally {
      final boolean _ = Thread.interrupted();
    }
  }
}
