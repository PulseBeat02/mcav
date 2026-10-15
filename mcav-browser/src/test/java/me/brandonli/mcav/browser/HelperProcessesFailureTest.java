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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class HelperProcessesFailureTest {

  @Test
  void shouldNotLeaveLaterHelpersRunningAfterAFailureSharedByTwoListeners() {
    final IllegalStateException sharedFailure = new IllegalStateException("shared listener failure");
    final HelperSession first = Mockito.mock(HelperSession.class);
    final HelperSession second = Mockito.mock(HelperSession.class);
    final HelperSession last = Mockito.mock(HelperSession.class);
    final long generation = HelperProcesses.requireOpen();
    Mockito.doAnswer(_ -> this.deregisterAndFail(first, sharedFailure))
      .when(first)
      .endAndClose(Mockito.anyString());
    Mockito.doAnswer(_ -> this.deregisterAndFail(second, sharedFailure))
      .when(second)
      .endAndClose(Mockito.anyString());
    Mockito.doAnswer(_ -> this.deregisterAndAnswerNothing(last))
      .when(last)
      .endAndClose(Mockito.anyString());
    try {
      final boolean firstRegistered = HelperProcesses.register(first, generation);
      assertTrue(firstRegistered);
      final boolean secondRegistered = HelperProcesses.register(second, generation);
      assertTrue(secondRegistered);
      final boolean lastRegistered = HelperProcesses.register(last, generation);
      assertTrue(lastRegistered);
      final RuntimeException thrown = assertThrows(RuntimeException.class, HelperProcesses::closeAll);
      final int helperCount = HelperProcesses.count();
      assertEquals(0, helperCount, "every registered helper must be ended");
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

  private Object deregisterAndFail(final HelperSession session, final IllegalStateException failure) {
    HelperProcesses.deregister(session);
    throw failure;
  }

  private @Nullable Object deregisterAndAnswerNothing(final HelperSession session) {
    HelperProcesses.deregister(session);

    return null;
  }
}
