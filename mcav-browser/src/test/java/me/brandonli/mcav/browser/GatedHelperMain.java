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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

// Waits for its gate before it connects, so a test can act while the server waits for the helper.
public final class GatedHelperMain {

  private static final Duration GATE_TIMEOUT = Duration.ofMinutes(1);
  private static final long POLL_PAUSE_MILLISECONDS = 10L;

  static final String GATE_PROPERTY = "mcav.test.gate";

  static final String STARTED_SUFFIX = ".started";

  private GatedHelperMain() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    final String gatePath = System.getProperty(GATE_PROPERTY);
    final Path gate = Path.of(gatePath);
    final Path fileName = gate.getFileName();
    final String startedName = "%s%s".formatted(fileName, STARTED_SUFFIX);
    final Path started = gate.resolveSibling(startedName);
    Files.createFile(started);

    final long deadline = System.nanoTime() + GATE_TIMEOUT.toNanos();
    while (!Files.exists(gate) && System.nanoTime() < deadline) {
      Thread.sleep(POLL_PAUSE_MILLISECONDS);
    }

    ScriptedEngine.main(args);
  }
}
