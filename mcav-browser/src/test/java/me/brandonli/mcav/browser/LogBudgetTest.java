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

/**
 * Tests {@link LogBudget} with a clock the test moves.
 */
final class LogBudgetTest {

  private final AtomicLong now = new AtomicLong(1_000L);
  private final List<String> log = new ArrayList<>();
  private final LogBudget budget = new LogBudget(this.now::get);

  private void line(final String text) {
    this.budget.log(() -> this.log.add(text), skipped -> this.log.add("skipped " + skipped));
  }

  private void lines(final int count) {
    for (int index = 0; index < count; index++) {
      this.line("line " + index);
    }
  }

  @Test
  void aBurstPassesAtOnceAndTheRestDoesNot() {
    this.lines(LogBudget.BURST + 10);
    assertEquals(LogBudget.BURST, this.log.size());
    assertEquals("line " + (LogBudget.BURST - 1), this.log.getLast());
  }

  @Test
  void oneMoreLinePassesPerSecondAfterTheCountOfTheLinesThatDidNot() {
    this.lines(LogBudget.BURST + 10);
    this.now.addAndGet(LogBudget.REFILL_NANOS - 1);
    this.line("too early");
    assertEquals(LogBudget.BURST, this.log.size());
    this.now.addAndGet(1);
    this.line("a second later");
    this.line("too early again");
    assertEquals(List.of("skipped 11", "a second later"), this.log.subList(LogBudget.BURST, this.log.size()));
  }

  @Test
  void aLongQuietTimeRefillsTheBurstAndNoMore() {
    this.lines(LogBudget.BURST);
    this.now.addAndGet(LogBudget.REFILL_NANOS * 1_000);
    this.lines(LogBudget.BURST + 1);
    assertEquals(2 * LogBudget.BURST, this.log.size());
  }

  @Test
  void aClockThatGoesBackRefillsNothing() {
    this.lines(LogBudget.BURST);
    this.now.addAndGet(-5 * LogBudget.REFILL_NANOS);
    this.line("never");
    assertEquals(LogBudget.BURST, this.log.size());
  }
}
