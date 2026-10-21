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
import java.util.concurrent.TimeUnit;

// Waits for its gate before it connects, so a test can act while the server waits for the helper.
public final class GatedHelperMain {

  static final String GATE_PROPERTY = "mcav.test.gate";

  static final String STARTED_SUFFIX = ".started";

  private GatedHelperMain() {}

  public static void main(final String[] args) throws IOException, InterruptedException {
    final Path gate = Path.of(System.getProperty(GATE_PROPERTY));
    Files.createFile(gate.resolveSibling(gate.getFileName() + STARTED_SUFFIX));
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (!Files.exists(gate) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    ScriptedEngine.main(args);
  }
}
