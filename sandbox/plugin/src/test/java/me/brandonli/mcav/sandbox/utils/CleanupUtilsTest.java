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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

final class CleanupUtilsTest {

  @Test
  void attemptsEveryCleanupAndPreservesAllFailures() {
    final List<Integer> cleaned = new ArrayList<>();
    final IllegalStateException first = new IllegalStateException("first");
    final IllegalArgumentException second = new IllegalArgumentException("second");
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      CleanupUtils.runAll(
        () -> {
          cleaned.add(1);
          throw first;
        },
        () -> {
          cleaned.add(2);
          throw second;
        },
        () -> cleaned.add(3)
      )
    );
    assertSame(first, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertArrayEquals(new Throwable[] { second }, suppressed);
    final List<Integer> expected = List.of(1, 2, 3);
    assertEquals(expected, cleaned);
  }

  @Test
  void preservesAnErrorAndDoesNotSuppressItAgainstItself() {
    final AssertionError failure = new AssertionError("failed cleanup");
    final List<Integer> cleaned = new ArrayList<>();
    final AssertionError thrown = assertThrows(AssertionError.class, () ->
      CleanupUtils.runAll(
        () -> {
          throw failure;
        },
        () -> {
          throw failure;
        },
        () -> cleaned.add(1)
      )
    );
    assertSame(failure, thrown);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length);
    final List<Integer> expected = List.of(1);
    assertEquals(expected, cleaned);
  }

  @Test
  void propagatesFatalFailuresWithoutAttemptingLaterCleanup() {
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal cleanup failure");
    final List<Integer> cleaned = new ArrayList<>();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
      CleanupUtils.runAll(
        () -> {
          throw fatal;
        },
        () -> cleaned.add(1)
      )
    );
    assertSame(fatal, thrown);
    final int count = cleaned.size();
    assertEquals(0, count);
  }

  @Test
  void acceptsNoActionsAndRejectsANullArray() {
    CleanupUtils.runAll();
    assertThrows(NullPointerException.class, () -> CleanupUtils.runAll((Runnable[]) null));
    UtilityClassAssertions.assertNotInstantiable(CleanupUtils.class);
  }
}
