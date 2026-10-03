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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class HelperProcessesFailureTest {

  @Test
  void aFailureSharedByTwoListenersDoesNotLeaveLaterHelpersRunning() {
    final IllegalStateException sharedFailure = new IllegalStateException("shared listener failure");
    final HelperSession first = Mockito.mock(HelperSession.class);
    final HelperSession second = Mockito.mock(HelperSession.class);
    final HelperSession last = Mockito.mock(HelperSession.class);
    final long generation = HelperProcesses.requireOpen();
    Mockito.doAnswer(invocation -> {
      HelperProcesses.deregister(first);
      throw sharedFailure;
    })
      .when(first)
      .endAndClose(Mockito.anyString());
    Mockito.doAnswer(invocation -> {
      HelperProcesses.deregister(second);
      throw sharedFailure;
    })
      .when(second)
      .endAndClose(Mockito.anyString());
    Mockito.doAnswer(invocation -> {
      HelperProcesses.deregister(last);
      return null;
    })
      .when(last)
      .endAndClose(Mockito.anyString());
    try {
      assertTrue(HelperProcesses.register(first, generation));
      assertTrue(HelperProcesses.register(second, generation));
      assertTrue(HelperProcesses.register(last, generation));
      final RuntimeException thrown = assertThrows(RuntimeException.class, HelperProcesses::closeAll);
      assertEquals(0, HelperProcesses.count(), "every registered helper must be ended");
      assertSame(sharedFailure, thrown, "the original listener failure remains the primary failure");
      assertEquals(0, thrown.getSuppressed().length);
      Mockito.verify(last).endAndClose(Mockito.anyString());
    } finally {
      HelperProcesses.deregister(first);
      HelperProcesses.deregister(second);
      HelperProcesses.deregister(last);
      HelperProcesses.open();
    }
  }
}
