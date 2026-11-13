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
package me.brandonli.mcav.browser.testing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StandardErrorTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(StandardErrorTest.class);

  @Test
  void shouldRecordWhatTheLoggerPrintsUntilItIsClosed() {
    final String recordedMessage = "Recorded while capturing.";
    final String lateMessage = "Printed after the capture.";
    final PrintStream previous = System.err;
    final StandardError capture = new StandardError();
    try (capture) {
      LOGGER.warn(recordedMessage);
    }
    LOGGER.warn(lateMessage);

    final String output = capture.text();
    final boolean recorded = output.contains(recordedMessage);
    final boolean recordedAfterClose = output.contains(lateMessage);
    final PrintStream restored = System.err;
    assertTrue(recorded, output);
    assertFalse(recordedAfterClose, output);
    assertSame(previous, restored);
  }
}
