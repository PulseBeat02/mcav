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
package me.brandonli.mcav.bukkit.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

final class LogCaptureTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

  @Test
  void shouldRecordEveryEventUntilClosedAndRestoreTheLevel() throws InterruptedException {
    final Logger logger = LogManager.getLogger(LogCaptureTest.class);
    final Level before = logger.getLevel();
    final IllegalStateException failure = new IllegalStateException("Second failure!");
    final LogCapture.RecordedEvent first = new LogCapture.RecordedEvent(Level.WARN, "First event.", null);
    final LogCapture.RecordedEvent second = new LogCapture.RecordedEvent(Level.DEBUG, "Second event.", failure);
    final LogCapture logs = LogCapture.capture(LogCaptureTest.class);
    try (logs) {
      logger.warn("First event.");
      logger.debug("Second event.", failure);

      final LogCapture.RecordedEvent firstEvent = logs.awaitEvent(TIMEOUT);
      final LogCapture.RecordedEvent secondEvent = logs.awaitEvent(TIMEOUT);
      assertEquals(first, firstEvent);
      assertEquals(second, secondEvent);
    }

    logger.warn("After close.");

    final List<LogCapture.RecordedEvent> events = logs.getEvents();
    final List<LogCapture.RecordedEvent> expected = List.of(first, second);
    final Level after = logger.getLevel();
    assertEquals(expected, events);
    assertEquals(before, after);
  }

  @Test
  void shouldFailWhenNothingIsLoggedBeforeTheTimeout() throws InterruptedException {
    try (final LogCapture logs = LogCapture.capture(LogCapture.class)) {
      final AssertionError thrown = assertThrows(AssertionError.class, () -> logs.awaitEvent(SHORT_TIMEOUT));
      final String message = thrown.getMessage();
      assertEquals("No event was logged before the PT0.05S timeout ran out!", message);
    }
  }
}
