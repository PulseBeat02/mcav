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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class LogBudgetTest {

  private static final String LINE_PREFIX = "line ";

  private final AtomicLong now = new AtomicLong(1_000L);
  private final List<String> log = new ArrayList<>();
  private final LogBudget budget = new LogBudget(this.now::get);

  private void line(final String text) {
    this.budget.log(() -> this.log.add(text), skipped -> this.log.add("skipped " + skipped));
  }

  private void lines(final int count) {
    for (int index = 0; index < count; index++) {
      this.line(LINE_PREFIX + index);
    }
  }

  @Test
  void shouldPassABurstAtOnceAndNotTheRest() {
    this.lines(LogBudget.BURST + 10);
    final int lineCount = this.log.size();
    assertEquals(LogBudget.BURST, lineCount);

    final String lastLine = this.log.getLast();
    final String expectedLastLine = LINE_PREFIX + (LogBudget.BURST - 1);
    assertEquals(expectedLastLine, lastLine);
  }

  @Test
  void shouldPassOneMoreLinePerSecondAfterTheCountOfTheLinesThatDidNot() {
    this.lines(LogBudget.BURST + 10);
    this.now.addAndGet(LogBudget.REFILL_NANOSECONDS - 1);
    this.line("too early");
    final int lineCount = this.log.size();
    assertEquals(LogBudget.BURST, lineCount);

    this.now.addAndGet(1);
    this.line("a second later");
    this.line("too early again");
    final List<String> laterLines = this.log.subList(LogBudget.BURST, this.log.size());
    final List<String> expectedLaterLines = List.of("skipped 11", "a second later");
    assertEquals(expectedLaterLines, laterLines);
  }

  @Test
  void shouldCountOnlyTheLinesSkippedSinceTheLastCount() {
    this.lines(LogBudget.BURST + 10);
    this.now.addAndGet(LogBudget.REFILL_NANOSECONDS);
    this.line("a second later");
    this.lines(3);
    this.now.addAndGet(LogBudget.REFILL_NANOSECONDS);
    this.line("two seconds later");
    final List<String> laterLines = this.log.subList(LogBudget.BURST, this.log.size());
    final List<String> expectedLaterLines = List.of("skipped 10", "a second later", "skipped 3", "two seconds later");
    assertEquals(expectedLaterLines, laterLines);
  }

  @Test
  void shouldRefillTheBurstAndNoMoreAfterALongQuietTime() {
    this.lines(LogBudget.BURST);
    this.now.addAndGet(LogBudget.REFILL_NANOSECONDS * 1_000);
    this.lines(LogBudget.BURST + 1);
    final int lineCount = this.log.size();
    final int expectedLineCount = 2 * LogBudget.BURST;
    assertEquals(expectedLineCount, lineCount);
  }

  @Test
  void shouldRefillNothingWhenTheClockGoesBack() {
    this.lines(LogBudget.BURST);
    this.now.addAndGet(-5 * LogBudget.REFILL_NANOSECONDS);
    this.line("never");
    final int lineCount = this.log.size();
    assertEquals(LogBudget.BURST, lineCount);
  }
}
